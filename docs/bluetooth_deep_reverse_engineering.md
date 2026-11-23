# Deep Reverse Engineering & Cyber Security Architecture of Bluetooth Stacks
### Windows NT Kernel (`bthport.sys`, `hidbth.sys`, `bthpan.sys`) & Android Fluoride (`libbluetooth_jni.so`)

---

## 1. Executive Summary & Threat Landscape

Bluetooth is one of the most ubiquitous, complex, and privileged wireless attack surfaces in modern operating systems. Spanning from physical 2.4 GHz baseband controllers up to kernel device drivers and user-space daemons, the Bluetooth protocol stack handles everything from unauthenticated discovery beacons and human interface devices (HID) to high-speed audio, virtual network adapters (PAN/BNEP), and raw socket multiplexing.

For security researchers, penetration testers, and reverse engineers ("cyber persons"), understanding the low-level boundaries between **Host Controller Interface (HCI)**, **Logical Link Control and Adaptation Protocol (L2CAP)**, **Host Security Managers (SMP/BTM)**, and **OS Driver Dispatchers** is paramount.

This research document details the low-level reverse-engineered architecture of both the **Windows NT Bluetooth Driver Subsystem** and the **Android Native Bluetooth Stack (Fluoride/Floss in Android 15/16)**. It exposes internal driver functions, IOCTL control codes, JNI entry points, security mitigation hooks (KNOB/BIAS), and attack surface vectors.

---

## 2. Windows NT Bluetooth Subsystem Reverse Engineering

```
┌────────────────────────────────────────────────────────┐
│                   User-Mode Applications               │
│  (Win32 Apps, WinRT Windows.Devices.Bluetooth, Python)  │
└───────────────────────────┬────────────────────────────┘
                            │
┌───────────────────────────▼────────────────────────────┐
│      BluetoothApis.dll  /  Windows.Devices.Bluetooth    │
│  (98 User-Mode Export Functions & GATT Client Engine)  │
└───────────────────────────┬────────────────────────────┘
                            │ RPC / ALPC
┌───────────────────────────▼────────────────────────────┐
│         bthserv.dll (Bluetooth Support Service)        │
│    (Pairing Broker, Swift Pair Dispatcher, Telemetry)  │
└───────────────────────────┬────────────────────────────┘
                            │ NtDeviceIoControlFile
════════════════════════════╪════════════════════════════ Kernel Boundary
                            │
┌───────────────────────────▼────────────────────────────┐
│                      bthport.sys                       │
│    (Core Miniport & Protocol Manager: L2CAP, SDP, HCI) │
└──────┬────────────────────┬────────────────────┬───────┘
       │                    │                    │
┌──────▼───────┐    ┌───────▼──────┐     ┌───────▼──────┐
│  hidbth.sys  │    │  bthpan.sys  │     │ BthA2dp.sys  │
│ (HID Driver) │    │(BNEP TCP/IP) │     │ (Audio/LE)   │
└──────┬───────┘    └───────┬──────┘     └───────┬──────┘
       │                    │                    │
┌──────▼────────────────────▼────────────────────▼───────┐
│                      bthenum.sys                       │
│           (Bluetooth Bus Device Enumerator)            │
└───────────────────────────┬────────────────────────────┘
                            │
┌───────────────────────────▼────────────────────────────┐
│                      BTHUSB.SYS                        │
│   (Host Controller Transport: Bulk, Int, Isoch URBs)   │
└───────────────────────────┬────────────────────────────┘
                            │ USB / PCIe
┌───────────────────────────▼────────────────────────────┐
│             Bluetooth HCI Controller Hardware          │
└────────────────────────────────────────────────────────┘
```

### 2.1 The Windows Bluetooth Driver Stack

The Windows Bluetooth architecture is organized as a layered WDM/KMDF driver tree:

1. **`BTHUSB.SYS` (Transport Driver)**:
   - Implements USB endpoint mapping for the standard Bluetooth USB interface class (`Class 0xE0, SubClass 0x01, Protocol 0x01`).
   - Manages distinct URB pipes:
     - Endpoint `0x00` (Control): HCI commands.
     - Endpoint `0x81` (Interrupt IN): HCI event notifications.
     - Endpoint `0x02` (Bulk OUT) / `0x82` (Bulk IN): ACL data packets for L2CAP.
     - Endpoint `0x03` / `0x83` (Isochronous): Synchronous Connection-Oriented (SCO/eSCO) voice data.

2. **`bthport.sys` (Miniport & Protocol Manager)**:
   - The central engine of the Windows Bluetooth stack (2.37 MB binary).
   - Multiplexes L2CAP channels (PSMs), maintains ACL connection handle tables, parses Extended Inquiry Responses (EIR), and enforces pairing key state machines.
   - Houses the hardware offloading engines:
     - `BthLEAdvertiseRequest`: Offloads non-connectable/connectable BLE advertisements.
     - `BthLEAdvFilterHwOffloadLimit`: Manages controller advertising filter rule capacity.
     - `BthLEHwMonitorUuidPattern` & `BthLEHwMonitorBthAddrPattern`: Hardware RSSI geofencing and UUID pattern matching without waking the host CPU.
     - `BthIRKResolution`: Identity Resolving Key (IRK) hardware offload for resolving Resolvable Private Addresses (RPA).

3. **`bthpan.sys` (Bluetooth Personal Area Network)**:
   - Provides NDIS-compliant network miniport interfaces over Bluetooth.
   - Encapsulates Ethernet frames (DIX / IEEE 802.3) into Bluetooth Network Encapsulation Protocol (BNEP) packets transmitted over L2CAP PSM `0x000F`.
   - **Reverse-Engineered IOCTLs**:
     - `IOCTL_PAN_CONNECT`: Requests establishment of a BNEP tunnel to a target Bluetooth MAC address.
     - `IOCTL_PAN_DISCONNECT`: Teardown of the active BNEP L2CAP link.
     - `IOCTL_PAN_CONFIG_SET` / `IOCTL_PAN_CONFIG_QUERY`: Sets network roles (PANU - PAN User, NAP - Network Access Point, GN - Group Ad-hoc Network).
     - `IOCTL_PAN_ENUM_CONNECTIONS`: Enumerates active bridged IP interfaces.
     - `IOCTL_PAN_CONNECTION_NOTIFICATION_REGISTER`: Registers asynchronous event handles for link state changes.

4. **`hidbth.sys` (Bluetooth Human Interface Device Driver)**:
   - Converts raw L2CAP data channels into standard Windows HID report IRPs (`IRP_MJ_READ`, `IOCTL_HID_GET_REPORT`, `IOCTL_HID_SET_REPORT`).
   - Connects two mandatory L2CAP channels:
     - **Control Channel (PSM `0x0011`)**: Receives HID commands (`GET_REPORT`, `SET_REPORT`, `GET_IDLE`, `SET_PROTOCOL`).
     - **Interrupt Channel (PSM `0x0013`)**: Low-latency asynchronous stream carrying mouse movement packets, keystroke state arrays, and gamepad thumbstick reports.

5. **`BthA2dp.sys` (Advanced Audio Distribution & LE Audio)**:
   - Audio streaming driver interfacing with `portcls.sys` and Windows Audio Engine.
   - Implements AVDTP (Audio/Video Distribution Transport Protocol) on PSM `0x0019`.
   - Contains reverse-engineered routines for stream buffering:
     - `L2capChannelHandleSessionId`: Ties audio endpoint sessions to underlying L2CAP channels.
     - `L2capOverflow`: Handles jitter buffer exhaustion and dynamic bitpool throttling for SBC/AAC/LC3 codecs.

---

### 2.2 `BluetoothApis.dll` Exported Functions Breakdown

`BluetoothApis.dll` provides 98 user-mode Win32 exports. The most critical reverse-engineered routines include:

| Function Export | Technical Mechanism | Cyber Security Significance |
| :--- | :--- | :--- |
| `BluetoothIsSwiftPairEnabledByDefault` | Checks registry `HKLM\Software\Microsoft\Windows\CurrentVersion\Bluetooth` for Swift Pair telemetry flags. | Allows detecting whether the host machine processes unauthenticated BLE manufacturer beacons (`0x0006`). |
| `BluetoothRegisterForAuthenticationEx` | Registers user-mode pairing callbacks (`PFN_AUTHENTICATION_CALLBACK_EX`) for Just Works, Numeric Comparison, Passkey, and OOB pairing. | Interception point for credential harvesting or custom pairing brokers. |
| `BluetoothSendAuthenticationResponseEx` | Sends cryptographic PIN, passkey, or numeric comparison confirmation back to `bthserv.dll`. | Used in automated authentication bypass testing and fuzzing. |
| `BluetoothGATTRegisterEvent` | Subscribes to GATT characteristic value change notifications via WinRT background triggers. | Target for eavesdropping on unencrypted BLE medical, IoT, or telemetry peripherals. |
| `BthpEnableA2DPIfPresent` | Undocumented helper enabling AVDTP audio sink/source profiles upon device enumeration. | Reverse-engineered hook to pivot Bluetooth audio sinks into recording devices. |
| `BthpShouldForceAuthentication` | Internal validation check determining if a requested service requires Man-In-The-Middle (MITM) protection. | Auditing target for finding authorization bypasses on legacy profiles. |
| `BluetoothSppEnableIncomingComPort` | Configures virtual COM port mapping (`\\.\COMx`) backed by RFCOMM channels. | Legacy persistence and command-and-control communication channel. |

---

### 2.3 Kernel Security Safeguards in `bthport.sys`

Deep analysis of strings and dispatch symbols in `bthport.sys` reveals the implementation of key anti-exploitation countermeasures:

1. **`HciReadEncryptionKeySize` (KNOB Attack Mitigation - CVE-2019-9506)**:
   - In the Key Negotiation of Bluetooth (KNOB) vulnerability, attackers forced the negotiated encryption key entropy down to a single byte (8 bits).
   - In `bthport.sys`, `HciReadEncryptionKeySize` queries the controller via HCI opcode `0x1408` (`HCI_Read_Encryption_Key_Size`).
   - If the returned key length is less than **7 octets (56 bits)**, Windows immediately terminates the ACL connection with `HciDisconnectResult` and logs an event.

2. **`HciAuthenticatedPayloadTimeout` (BIAS Attack Mitigation - CVE-2020-10135)**:
   - Bluetooth Impersonation Attacks (BIAS) exploit master-slave role switches without mutual authentication.
   - Windows sets the Authenticated Payload Timeout (`HCI_Write_Authenticated_Payload_Timeout`) to ensure cryptographically verified MIC packets arrive periodically; failure forces link termination.

---

## 3. Android Bluetooth Stack (Fluoride) Reverse Engineering

In Android (tested against extracted `libbluetooth_jni.so` from Realme RMX3998, Android 16), the stack is based on Google's **Fluoride** (AOSP C++ Bluetooth stack).

```
┌────────────────────────────────────────────────────────┐
│           Android Java Framework Applications          │
│       (BluetoothManager, BluetoothHidDevice, Gamepad)  │
└───────────────────────────┬────────────────────────────┘
                            │ AIDL / Binder
┌───────────────────────────▼────────────────────────────┐
│      com.android.bluetooth.btservice.AdapterService    │
└───────────────────────────┬────────────────────────────┘
                            │ JNI Registration
┌───────────────────────────▼────────────────────────────┐
│                  libbluetooth_jni.so                   │
│   (JNI Bindings: android_bluetooth_btservice_*, etc.)  │
└───────────────────────────┬────────────────────────────┘
                            │
┌───────────────────────────▼────────────────────────────┐
│              BTA (Bluetooth Application Engine)         │
│  (bta_hd_api_enable, bta_gattc_start_discover, bta_dm) │
└───────────────────────────┬────────────────────────────┘
                            │
┌───────────────────────────▼────────────────────────────┐
│             BTM (Bluetooth Device Manager)             │
│            & SMP (Security Manager Protocol)           │
│   (smp_proc_confirm, smp_collect_peer_io_capabilities) │
└───────────────────────────┬────────────────────────────┘
                            │
┌───────────────────────────▼────────────────────────────┐
│               L2CAP (Logical Link Control)             │
│  (L2CA_ConnectCreditBasedReq, L2CA_SubrateRequest)     │
└───────────────────────────┬────────────────────────────┘
                            │
┌───────────────────────────▼────────────────────────────┐
│                      HCI Layer                         │
│       (hci_layer.cc, btsnoop_hci, hci_handle_mtk)      │
└───────────────────────────┬────────────────────────────┘
                            │ HIDL / AIDL HAL
┌───────────────────────────▼────────────────────────────┐
│             android.hardware.bluetooth HAL             │
└────────────────────────────────────────────────────────┘
```

### 3.1 Fluoride Native Architecture & Internal Functions

Our symbol analysis of `libbluetooth_jni.so` extracted **808 BTA symbols**, **274 SMP symbols**, **106 HID symbols**, and **59 L2CAP symbols**.

#### A. L2CAP Layer (`L2CA_*`)
- `L2CA_ConnectCreditBasedReq`:
  - Implements **Enhanced Credit-Based Flow Control (EATT)** introduced in Bluetooth 5.2.
  - Unlike legacy L2CAP channels that share a single FIFO queue, credit-based channels permit multiple concurrent GATT and data bearers with independent credit allocations, preventing Head-of-Line (HoL) blocking.
- `L2CA_SubrateRequest`:
  - Implements **Bluetooth 5.3 Connection Subrating**.
  - Allows peripherals (e.g. gamepads or sensors) to instantly toggle between ultra-low power latency (e.g. 1000ms subrate for standby) and high burst throughput (7.5ms interval for gyro motion) without the high overhead of full connection parameter renegotiation.
- `L2CA_SetTxPriority`:
  - Adjusts transmission packet priority inside the controller queues (`L2CAP_CHNL_PRIORITY_HIGH` vs `LOW`), ensuring motion and keystroke packets jump ahead of heavy background audio or file transfers.

#### B. Native HID Device Role (`bta_hd_*`)
- `bta_hd_api_enable`:
  - Initializes the internal Human Interface Device profile state machine in peripheral mode (`bta_hd_cb`).
- `bta_hd_register_act`:
  - Registers the Bluetooth SDP record for HID, setting subclass `0x05` (Peripheral), modifier flags (Virtual Cable, Reconnect Initiate), and uploading the raw binary HID Report Descriptor.
- `HID_DevSendReport`:
  - Transmits raw HID reports over L2CAP interrupt channel (PSM `0x0013`). Prepends the HID Header byte (`0xA1` for Input Report) followed by report payload.

#### C. BNEP Tunneling Engine (`BNEP_*`)
- `BNEP_Connect`:
  - Initiates BNEP control connection (`BNEP_SETUP_CONNECTION_REQUEST_MSG`) with target Bluetooth UUID (PANU `0x1115`, NAP `0x1116`, GN `0x1117`).
- `BNEP_FILTER_NET_TYPE_SET_MSG`:
  - Injects network protocol type filters into the L2CAP link (e.g., restricting frames to IPv4 `0x0800`, ARP `0x0806`, IPv6 `0x86DD`) to reduce airtime bandwidth consumption.
- `BNEP_WriteBuf`:
  - Direct zero-copy transmit of network buffers across the Bluetooth radio.

#### D. Security Manager Protocol (`smp_*`)
- `smp_collect_peer_io_capabilities`:
  - Extracts the peer device's input/output capabilities (`DisplayOnly`, `DisplayYesNo`, `KeyboardOnly`, `NoInputNoOutput`).
  - Cross-references peer capabilities with local capabilities to select the association model:
    - If either device is `NoInputNoOutput`, forces **Just Works** (unauthenticated, vulnerable to active MITM).
    - If both have display/keyboard, initiates **Numeric Comparison** or **Passkey Entry**.
- `smp_proc_confirm`:
  - Computes and verifies the cryptographic confirmation value using AES-CMAC ($c1$ / $s1$ function).

#### E. MediaTek Vendor Layer (`hci_handle_mtk`)
- In `libbluetooth_jni.so`, the presence of `hci_handle_mtk` highlights vendor-specific HCI extensions provided by MediaTek chipsets:
  - Custom vendor opcodes for dynamic baud rate switching (e.g., boosting UART from 115200 to 3.2 Mbps for A2DP LDAC or BNEP file transfers).
  - Controller-level firmware RAM patching and dynamic sleep clock calibration.

---

## 4. Cyber Security Threat Modeling & Attack Surface

Understanding these internal subsystems provides deep insight into both offensive exploitation surfaces and defensive hardening strategies:

### 4.1 Unauthenticated BLE Swift Pair Injection
- **Mechanism**: Windows listens continuously for BLE Manufacturer Advertising Data with Company ID `0x0006` and Scenario ID `0x03`.
- **Attack Vector**: An adversary can broadcast spoofed Swift Pair advertisements carrying the display name of trusted hardware (e.g., `"Microsoft Modern Mobile Mouse"` or `"Surface Precision Keyboard"`).
- **Impact**: Generates a high-priority Windows shell notification toast on all nearby PCs. If an unsuspecting user clicks "Connect", the workstation initiates an unauthenticated pairing sequence, opening an RFCOMM or HID channel directly to the rogue device.
- **Defensive Mitigation**: Group Policy disabling Swift Pair:
  `HKLM\Software\Policies\Microsoft\Windows\Bluetooth\SwiftPair = 0`.

### 4.2 BNEP Network Bridging & Air-Gap Bypasses
- **Mechanism**: BNEP translates Ethernet layer 2 frames across Bluetooth L2CAP.
- **Attack Vector**: If an attacker gains code execution on a mobile device or laptop with Bluetooth enabled, they can establish a BNEP tunnel (`IOCTL_PAN_CONNECT` or `BNEP_Connect`) to a target workstation.
- **Impact**: Bypasses corporate Wi-Fi network isolation, 802.1X enterprise NAC policies, and captive portals by establishing a covert TCP/IP tunnel over Bluetooth without requiring Wi-Fi association.
- **Defensive Mitigation**: Disable the `bthpan.sys` service or restrict Bluetooth PAN network adapter bindings in Windows Network Connections (`ncpa.cpl`).

### 4.3 Bluetooth BadUSB / HID Injection
- **Mechanism**: Android's `BluetoothHidDevice` API allows standard smartphones to register as Bluetooth HID peripherals.
- **Attack Vector**: When paired with a host, the phone can transmit arbitrary HID reports via `HID_DevSendReport` or `SendInput` emulation.
- **Impact**: Emulates keystrokes at hundreds of words per minute, executing `Win + R`, powershell commands, or credential exfiltration before a user can unplug or lock the workstation.
- **Defensive Mitigation**: Windows Endpoint Security / Device Guard policies restricting Bluetooth HID device classes or requiring Secure Connections with MITM protection (`BthpShouldForceAuthentication`).

### 4.4 Hardware RSSI Proximity Spoofing
- **Mechanism**: Proximity auto-lock relies on RSSI thresholds reported by HCI (`HCI_Read_RSSI` or `BthLEHwMonitorBthAddrPattern`).
- **Attack Vector**: An attacker in the vicinity of a target laptop can amplify the victim phone's Bluetooth signals using high-gain directional antennas (e.g., 2.4 GHz Yagi) or a BLE repeater.
- **Impact**: The laptop continues to perceive the phone's signal as strong (> -70 dBm) even after the user has walked away, keeping the workstation unlocked and vulnerable to physical access.
- **Defensive Mitigation**: Never rely solely on RSSI for high-security auto-lock; pair RSSI monitoring with Bluetooth 5.1/6.0 **Channel Sounding** (phase-based distance measurement and round-trip time of flight), which cannot be spoofed by simple signal amplification.

---

## 5. Reverse Engineering Reference Table

| Target Binary / Driver | Component / Symbol | Category | Description & Reversal Finding |
| :--- | :--- | :--- | :--- |
| **`bthport.sys`** | `HciReadEncryptionKeySize` | Security Hook | Enforces minimum 7-byte entropy check against KNOB attacks (CVE-2019-9506). |
| **`bthport.sys`** | `BthLEHwMonitorUuidPattern` | HW Offload | Instructs the Bluetooth controller to filter UUIDs in silicon without CPU interrupts. |
| **`bthport.sys`** | `BthIRKResolution` | HW Privacy | Resolves BLE Resolvable Private Addresses (RPA) in controller hardware. |
| **`bthpan.sys`** | `IOCTL_PAN_CONNECT` | IOCTL Code | Requests creation of a BNEP L2CAP link for TCP/IP network tunneling. |
| **`hidbth.sys`** | `BTH_ERROR_SUCCESS` | Status Codes | Exhaustive enum of Bluetooth HCI specification error responses mapped to NTSTATUS. |
| **`BluetoothApis.dll`**| `BluetoothIsSwiftPairEnabledByDefault` | Export | Evaluates registry policies governing Windows shell Swift Pair advertisement processing. |
| **`BluetoothApis.dll`**| `BluetoothRegisterForAuthenticationEx` | Export | Registers user-mode pairing dispatchers for PIN, Numeric Comparison, and Passkey models. |
| **`libbluetooth_jni.so`**| `L2CA_ConnectCreditBasedReq` | L2CAP Core | Establishes Enhanced Credit-Based Flow Control (EATT) L2CAP channels for high throughput. |
| **`libbluetooth_jni.so`**| `L2CA_SubrateRequest` | Bluetooth 5.3 | Dynamically changes connection subrating factors for burst latency scaling. |
| **`libbluetooth_jni.so`**| `bta_hd_register_act` | HID Device | Registers HID SDP profile record and report descriptor on the phone's Fluoride stack. |
| **`libbluetooth_jni.so`**| `BNEP_Connect` | Network | Initiates low-level BNEP setup connection over L2CAP PSM `0x000F`. |
| **`libbluetooth_jni.so`**| `smp_collect_peer_io_capabilities` | Pairing Engine | Evaluates peer and host I/O capabilities to choose Just Works vs Numeric Comparison. |
| **`libbluetooth_jni.so`**| `hci_handle_mtk` | Vendor Hook | MediaTek chipset-specific HCI extensions for custom baud rate and firmware patching. |

---

## 6. How to Perform Live Bluetooth Stack Auditing

To audit and inspect live Bluetooth traffic and driver behavior on Windows and Android:

1. **Windows Kernel ETW / Netsh Trace**:
   ```cmd
   netsh trace start scenario=Bluetooth capture=yes persistent=no traceFile=C:\bt_trace.etl
   :: (Perform Bluetooth action / pairing / streaming)
   netsh trace stop
   ```
   Convert the resulting `.etl` file to `.pcap` using Microsoft's `etl2pcap` utility and inspect full HCI packets, L2CAP fragmentation, and BNEP payloads in Wireshark.

2. **Android Live HCI Snoop Log**:
   ```bash
   adb shell settings put secure bluetooth_btsnoop_default_mode full
   :: Toggle Bluetooth off and on in Android Settings
   :: (Execute app actions)
   adb pull /data/misc/bluetooth/logs/btsnoop_hci.log btsnoop_hci.log
   wireshark btsnoop_hci.log
   ```
   Inspects raw HCI UART packets transmitted between `libbluetooth_jni.so` and the hardware radio, including all credit allocations, SDP registrations, and SMP key exchanges.
