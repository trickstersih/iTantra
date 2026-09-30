# iTantra

**iTantra is an offline tactical communication app that turns nearby Android devices into a local communications network — without depending on the internet or cellular infrastructure.**

It is designed for situations where conventional communication may be unavailable, unreliable, or undesirable: remote operations, disaster response, field teams, large sites, and other environments where a group of people still needs to communicate when there is no working network infrastructure.

The app combines **Bluetooth Low Energy (BLE), Wi-Fi Direct, mesh routing, offline speech recognition, and offline text-to-speech** into one communication system.

---

## What iTantra Does

At its core, iTantra lets a group of nearby phones discover each other, form a squad, exchange messages, and communicate by voice — all while keeping the communication local to the devices.

### Core features

- **Offline text messaging** between nearby devices
- **Push-to-talk (PTT)** communication for a walkie-talkie style experience
- **Continuous Call mode** that continuously turns speech into messages
- **BLE communication** for local discovery, control, and data transfer
- **Wi-Fi Direct communication** for a higher-bandwidth local connection between devices
- **Mesh forwarding** so a message can travel through intermediate devices when the sender and recipient are not directly connected
- **Squad management** for organizing team members
- **Emergency SOS broadcasting** with priority handling and loud local playback
- **Device discovery and connection status** for nearby peers
- **Local notifications and unread-message tracking**
- **Multilingual offline speech processing** without sending audio to a cloud service

---

## Why Use Speech-to-Text and Text-to-Speech?

A normal walkie-talkie sends audio.

iTantra takes a different approach for its normal voice communication:

**Your voice → Speech-to-Text → Text packet → Mesh network → Text-to-Speech → Recipient's speaker**

This design has several advantages.

### 1. Much less data has to be transmitted

Raw audio is relatively expensive to transmit, especially across multiple wireless hops.

Instead of continuously streaming an audio signal, iTantra can send the resulting text as a compact message packet. That makes the data easier to move through a mesh network where bandwidth and connection quality can vary.

### 2. Speech can travel through the mesh more easily

Text packets are small enough to be forwarded from one device to another.

For example:

```text
Phone A ── Phone B ── Phone C
```

If A cannot directly reach C, B can relay the message.

The recipient then converts the received text back into speech locally.

### 3. Everything can stay offline

The speech recognition and speech synthesis models run **on the device**.

There is no requirement to send someone's voice to an online speech API just to understand or speak a message.

### 4. Language information travels with the message

Speech recognition produces text together with a language code.

The receiving device can use that language information to select the appropriate local TTS voice.

There is **no automatic translation** in the communication pipeline. A Hindi message remains a Hindi message, for example.

### Current speech pipeline

The project uses a lightweight, locally executed multilingual STT pipeline based around the **andr2** model and locally bundled TTS models.

The STT pipeline is configured for these language codes:

**Hindi, Gujarati, Marathi, Kannada, Malayalam, Tamil, Telugu, Odia, Bengali, and English.**

Because the models run locally, speech processing does not require an internet connection.

---

## Why Wi-Fi Direct?

BLE is extremely useful for discovering devices and maintaining local low-bandwidth communication, but it is not ideal for every type of data transfer.

**Wi-Fi Direct provides a local Wi-Fi connection directly between devices without requiring a conventional Wi-Fi router or internet connection.**

iTantra uses the two radios for different strengths:

| Technology | Role in iTantra |
|---|---|
| **Bluetooth Low Energy** | Discovery, nearby device communication, control, and low-bandwidth mesh links |
| **Wi-Fi Direct** | Higher-bandwidth local device-to-device communication using an IP/TCP connection |
| **Mesh layer** | Routes packets across multiple connected devices |

Wi-Fi Direct is therefore **not being used to access the internet**. It creates a local communication link between participating devices.

The app also monitors the Wi-Fi Direct connection and attempts to recover connections when devices move out of range or the Wi-Fi radio is toggled.

---

## Mesh Communication

The main idea behind the mesh system is simple:

**A device does not always need a direct connection to the final recipient.**

Consider three devices:

```text
A ───── B ───── C
```

A message from A can be received by B and then forwarded toward C.

This allows the practical communication area to extend beyond the direct range of a single connection.

The mesh layer handles things such as:

- packet forwarding
- hop limits / TTL
- duplicate detection
- identifying the original sender
- preventing packets from circulating indefinitely

This makes the network useful even when some members of the team are separated by distance or obstacles.

---

## Squad Communication

iTantra includes a squad system for managing team members.

A device can discover nearby peers and add them to its squad. Squad membership is persistent, and the application can attempt to re-establish connections to squad members after temporary connection loss.

The UI distinguishes between:

- **available devices** discovered nearby
- **squad devices** that have been added to the local squad
- **direct connections**
- **relayed devices** that are reachable through the mesh

This lets the user see not only who is nearby, but also how a device is currently reachable.

---

## Emergency SOS

Emergency communication is treated differently from normal messaging.

An emergency can be triggered through the app's panic/emergency interaction. The recorded description is converted to text locally and packaged as a high-priority emergency message.

The SOS is then broadcast rather than being restricted to only the normal squad-message target set.

Emergency transmission is intended to reach:

- currently available direct devices
- devices already in the squad
- other reachable devices through mesh forwarding

On the receiving device, the emergency is presented as a high-priority alert and can trigger loud local speech playback so that it is difficult to miss.

---

## A Typical Voice Communication Flow

For PTT communication, the overall flow looks like this:

```text
┌─────────────┐
│ Microphone  │
└──────┬──────┘
       ↓
┌──────────────────┐
│ Local STT Model  │
│  Speech → Text   │
└──────┬───────────┘
       ↓
┌──────────────────┐
│ Text Packet      │
│ + Language Code  │
└──────┬───────────┘
       ↓
┌────────────────────────┐
│ BLE / Wi-Fi Direct     │
│ + Mesh Forwarding      │
└──────┬─────────────────┘
       ↓
┌──────────────────┐
│ Receiving Device │
└──────┬───────────┘
       ↓
┌──────────────────┐
│ Local TTS Model  │
│  Text → Speech   │
└──────┬───────────┘
       ↓
┌─────────────┐
│   Speaker   │
└─────────────┘
```

The important part is that the network transports **text**, not a continuous voice stream, for normal PTT/Call communication.

---

## Designed for Offline Operation

A major goal of iTantra is to keep the communication system functional without relying on infrastructure outside the participating devices.

The important processing therefore happens locally:

- speech recognition runs on-device
- text-to-speech runs on-device
- messages are transmitted directly between devices
- mesh forwarding happens locally
- Wi-Fi Direct does not require an internet connection

This also keeps speech data processing local instead of requiring a cloud speech service.

---

## Technology

iTantra is built for Android using **Kotlin** and a layered communication architecture.

The application combines:

- Android Bluetooth Low Energy APIs
- Android Wi-Fi Direct / local networking APIs
- local ONNX-based speech processing
- mesh packet routing and forwarding
- persistent device and squad state
- an Android UI for communication, discovery, and emergency handling

The implementation is structured so that communication transports and speech models can evolve independently.

---

## Current Development

iTantra is an active development project.

The current application already brings together:

**offline speech + BLE + Wi-Fi Direct + mesh communication + squad management + emergency broadcasting**

with ongoing work focused on making the mesh topology, discovery, routing, and reconnection behavior more robust.

---

## Project Goal

The long-term goal of iTantra is to provide a **self-contained local communication network made from ordinary Android phones**, capable of carrying text and voice-style communication even when traditional communication infrastructure cannot be relied upon.

> **No tower. No router. No cloud speech service. Just the devices around you.**
