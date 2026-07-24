# Smart Scale — AFE4300PNR + nRF52840 + ETA9741

Goal is 0 to 180 kilogram weight plus minus 0.1 kilogram after averaging and body fat plus minus 2 to 3 percent foot to foot. Bluetooth Low Energy to the Things app.

If you weigh without a phone, the scale saves the measurement to internal flash with a timestamp and shows saved on the display. Later you bring the phone near and press Sync in the app, the phone connects and wakes the scale and pulls stored history so no weigh in is lost.

## Block Diagram

```
USB Type C 5V -> ETA9741 -> battery -> 3.3V via inductor -> 3V3 system
3V3 -> ferrite bead -> 3V3 analog -> AFE4300
3V3 -> nRF52840 main chip, OLED display, accelerometer

Load cells 4x 50kg direct solder:
 Red to WS5 pin36, Black to WS6 pin37 ground
 Whites diagonal: front left plus rear right to WS1_P pin30, front right plus rear left to WS1_N pin31 via filter

Electrodes 4 stainless:
 Pogo pad -> 3.3k resistor -> protection diode to ground -> 1k resistor -> 100nF capacitor -> AFE4300
 I_OUTP pin13 to left heel drive, I_OUTN pin14 to right heel drive
 V_INP pin15 to left toe sense, V_INN pin16 to right toe sense

SPI 1MHz nRF to AFE4300, I2C 400kHz nRF to OLED and accelerometer
Clock tree - Option B (Dedicated AFE crystal):
 - Y1 32MHz HFXO (C23184 etc) -> nRF XC1/XC2 - BLE radio, MUST be 32MHz
 - Y2 32.768kHz LFXO C32346 -> nRF XL1/XL2 - RTC/offline timestamps
 - Y3 8MHz C12674 -> AFE4300 XIN/XOUT on U2 with C34/C35 27-33pF caps - nRF P0.16 NC (Option B)
PCB antenna to phone

### Clock Errata — FINAL MAP Y1/Y2/Y3 Option B
- nRF52840 spec: HFXO MUST be 32MHz for BLE/USB. C12674 8MHz HC-49S will not start HFXO; radio synthesizer fails.
- Y1 = 32MHz (C23184 etc) -> nRF XC1/XC2 mandatory.
- Y2 = C32346 = 32.768kHz (Epson Q13FC13500004) -> nRF XL1/XL2 LFXO for low-power RTC/offline timestamp.
- Y3 = C12674 = 8MHz -> AFE4300 XIN/XOUT only: typical XIN=8MHz for BIA 50kHz excitation and ADC timing.
- Original design intent (deprecated Option A): Y1=32MHz + Y2=32.768kHz both on nRF, plus nRF P0.16 PWM generates 8MHz for AFE. Now Option B: Y3 dedicated + C34/C35, P0.16 NC. Y1 still mandatory for BLE.
```

## Full BOM — Every component numbered (DO NOT substitute D1-D4)

### U — ICs
| Ref | LCSC | MPN | Purpose |
|---|---|---|---|
| U1 | C190794 | NRF52840-QIAA-R | Main MCU + BLE + USB |
| U2 | C528638 | AFE4300PNR | Weight + BIA AFE |
| U3 | C7465513 | ETA9741E8A | Charger + 3.3V buck |
| U4 | C110926 | LIS2DH12TR | Motion wake 1.2g interrupt - keep for battery life |

### J / E / W — Connectors and pads
| Ref | Type | Purpose |
|---|---|---|
| J1 | C165948 TYPE-C-31-M-12 | USB-C 5V + D+/D- |
| J2 | JST-PH2 2mm B2B-PH-K-S | LiPo 1000mAh battery, e.g. C247704 or similar |
| J3 | 4pin 1.0mm header | External OLED SSD1306 1.3" I2C |
| E1 | Pogo pad 6mm copper | Left heel drive electrode - stainless plate |
| E2 | Pogo pad | Right heel drive electrode |
| E3 | Pogo pad | Left toe sense electrode |
| E4 | Pogo pad | Right toe sense electrode |
| W1 | 3-pad: RED/BLK/WHT | Front Left load cell 50kg 3-wire direct solder |
| W2 | 3-pad | Front Right load cell |
| W3 | 3-pad | Rear Right load cell |
| W4 | 3-pad | Rear Left load cell |

Load cell wires glued for strain relief, no connector.

### Y / L / FB — Crystals / Magnetics — Option B (FIXED per jlcparts/LCSC)
| Ref | LCSC | Value | Purpose | Verified |
|---|---|---|---|---|
| Y1 | C23184 or C9005 or C13738 or C1457518 or C13267 | 32MHz 12pF ±20ppm 3225-4P 20ppm | nRF52840 HFXO XC1/XC2 - BLE RADIO MUST BE 32MHz | nRF52840 PS mandates 32MHz ±40ppm for BLE |
| Y2 | C32346 | 32.768kHz Q13FC13500004 Epson FC-135 32.768kHz ±20ppm 12.5pF 70kΩ SMD3215-2P | nRF LFXO XL1/XL2 - RTC / timestamp for offline weigh-ins + 1Hz ADV low-power | LCSC: "Crystal 32.768kHz ±20ppm 12.5pF 70kΩ SMD3215-2P" - https://github.com/yaqwsx/jlcparts |
| Y3 | C12674 | 8MHz ±20ppm 20pF HC-49S-SMD | AFE4300 dedicated XIN crystal on U2 XIN/XOUT with C34/C35 27-33pF load caps. Option B: nRF P0.16 NC. NOT on nRF XC1/XC2. | LCSC: "Crystal 8MHz ±20ppm 20pF HC-49S-SMD" |
| L1 | C5832372 | 2.2uH FTC252012S2R2MBCA | ETA9741 SW inductor - swap to Basic C3471785 SWPA252012S2R2MP if needed | |
| L2 | Generic 0402 | 2.7nH | nRF antenna matching | |
| FB1 | C1002 | GZ1608D601TF | 3V3 -> 3V3A analog filter | |

### D — Protection - CRITICAL, low capacitance only
| Ref | LCSC | MPN | Purpose | NOTE |
|---|---|---|---|---|
| D1 | C84374 | PESD5V0S1BL,315 | ESD for E1 left heel | 0.35pF, Vrwm 5V, Vc ~9V. Do NOT use SM4007PL/M7/PSM712/SMBJ - Vc 10-19V kills AFE, Cj 15-3000pF kills BIA accuracy |
| D2 | C84374 | PESD5V0S1BL,315 | ESD for E2 right heel | same |
| D3 | C84374 | PESD5V0S1BL,315 | ESD for E3 left toe sense | same - most sensitive for BIA accuracy |
| D4 | C84374 | PESD5V0S1BL,315 | ESD for E4 right toe sense | same |

If you must stay 100% Basic, source from ESD Suppressors category: C77481 ESD5Z5.0T1G SOD-523 or PRTR5V0U2X style 0.3pF. Category must be TVS/ESD not Diodes-General or 600W SMB.

### C — Capacitors (Base free except antenna)
| Ref | LCSC | Value | Where |
|---|---|---|---|
| C1 | C307331 | 100nF 50V 0402 | E1 electrode DC block - between R3 and U2 pin13 |
| C2 | C307331 | 100nF | E2 electrode DC block - between R4 and U2 pin14 |
| C3 | C307331 | 100nF | E3 electrode DC block - between R5 and U2 pin15 |
| C4 | C307331 | 100nF | E4 electrode DC block - between R6 and U2 pin16 |
| C5 | C307331 | 100nF | Load cell differential between WS1_P and WS1_N after R1/R2 |
| C6 | C307331 | 100nF | U1 nRF VDD decoupling near pin |
| C7 | C307331 | 100nF | U2 DVDD pin62 to AGND |
| C8 | C307331 | 100nF | U2 AVDD pin23 to AGND |
| C9 | C307331 | 100nF | U2 AVDD pin45 to AGND |
| C10 | C307331 | 100nF | U2 AVDD pin64 to AGND |
| C11 | C307331 | 100nF | U3 output star 3V3 to GND |
| C12 | C15195 | 10nF 50V 0402 | Load cell WS1_P common mode to AGND |
| C13 | C15195 | 10nF | Load cell WS1_N common mode to AGND |
| C14 | C15195 | 10nF | U2 FILT1 pin11 to AGND |
| C15 | C15195 | 10nF | U2 FILT2 pin12 to AGND |
| C16 | C15195 | 10nF | U1 spare decoupling |
| C17 | C15195 | 10nF | U1 P0.03 VBAT adc filter to GND |
| C18 | C23733 | 4.7uF 10V 0402 | WS5 excitation pin36 RED to AGND |
| C19 | C23733 | 4.7uF | U2 pin23 AVDD bulk to AGND |
| C20 | C23733 | 4.7uF | U2 pin45 AVDD bulk |
| C21 | C23733 | 4.7uF | U2 pin64 AVDD bulk |
| C22 | C23733 | 4.7uF | U2 pin62 DVDD bulk |
| C23 | C15525 | 10uF 6.3V 0402 | U3 VIN pin20 VBUS 5V to GND (USB input) |
| C24 | C15525 | 10uF | U3 VBAT pin to GND |
| C25 | C15525 | 10uF | U1 VDD bulk to GND |
| C26 | C59461 | 22uF 10V 0603 | U3 VOUT 3V3 bulk 1 at star node after L1 |
| C27 | C59461 | 22uF | U3 VOUT 3V3 bulk 2 at star |
| C28 | C12530 | 2.2uF 10V 0402 | U2 VREF internal pin to AGND |
| C29 | C52923 | 1uF 16V 0402 | U2 DVDD decoupling |
| C30 | C52923 | 1uF | U3 EN delay RC to GND (with R15) |
| C31 | Generic 0402 | 1pF | Antenna matching series from U1 ANT pin to L2 |
| C32 | Generic 0402 | 0.8pF | Antenna matching shunt to GND after L2 |
| C33 | C307331 | 100nF | J3 OLED VDD decoupling at header (optional, close to connector) |
| C34 | C15195 or 27-33pF NP0 0402 | 27-33pF NP0 | Y3 8MHz load cap XIN side to AGND - part of Option B, place <5mm to U2 pin58 |
| C35 | C15195 or 27-33pF NP0 0402 | 27-33pF NP0 | Y3 8MHz load cap XOUT side to AGND - part of Option B, place <5mm to U2 pin59 |

Y1 32MHz + Y2 32.768kHz load caps internal to nRF52840, no external needed (set HFXO caps via CONFIG). Y3 8MHz C12674 20pF CL requires C34/C35 27-33pF NP0 0402 to AGND near U2 XIN/XOUT; tune caps for ~8MHz (start 27pF, measure).

### R — Resistors (Base free except R24)
| Ref | LCSC | Value | Purpose |
|---|---|---|---|
| R1 | C11702 | 1k 1% 0402 | Weight filter WS1_P series - front left + rear right |
| R2 | C11702 | 1k | Weight filter WS1_N series - front right + rear left |
| R3 | C11702 | 1k | E1 second stage safety after D1 to C1 |
| R4 | C11702 | 1k | E2 second stage after D2 to C2 |
| R5 | C11702 | 1k | E3 second stage after D3 to C3 |
| R6 | C11702 | 1k | E4 second stage after D4 to C4 |
| R7 | C22978 | 3.3k 1% 0603 | E1 first stage near pad E1 to D1 node |
| R8 | C22978 | 3.3k | E2 first stage near pad E2 to D2 node |
| R9 | C22978 | 3.3k | E3 first stage near pad E3 to D3 node |
| R10 | C22978 | 3.3k | E4 first stage near pad E4 to D4 node |
| R11 | C25900 | 4.7k 0402 | I2C SDA pullup P0.30 to 3V3 |
| R12 | C25900 | 4.7k | I2C SCL pullup P0.31 to 3V3 |
| R13 | C25905 | 5.1k 0402 | USB-C CC1 to GND |
| R14 | C25905 | 5.1k | USB-C CC2 to GND |
| R15 | C25744 | 10k 0402 | U3 EN pullup to VBAT + delay with C30 |
| R16 | C25744 | 10k | U2 CS P0.14 pullup to 3V3 |
| R17 | C25744 | 10k | U2 DRDY P0.20 pullup to 3V3 |
| R18 | C25744 | 10k | U2 RESET P0.22 pullup to 3V3 |
| R19 | C25744 | 10k | U1 BOOT-ish / U4 SDO or NC pullup |
| R20 | C25744 | 10k | SW1 button pullup to 3V3 |
| R21 | C26083 | 1M 0402 | VBAT divider top VBAT -> P0.03 node |
| R22 | C26083 | 1M | VBAT divider bottom P0.03 node -> GND |
| R23 | C4109 | 2k 0402 | U3 ISET to GND = 500mA charge |
| R24 | C852624 | 1k 0.1% 0402 | U2 RREF pin18 to AGND - BIA calibration, keep 0.1% |

### Mechanical / Other
| Ref | Purpose |
|---|---|
| SW1 | C318884 tactile 5.1mm - P0.10 to GND, bootloader/tare/shipping |
| Load cells 4x | 50kg 3-wire Red Black White |
| Plates 4x | Stainless 304 50x60mm |
| Pogo 4x | 6mm spring to E1-E4 pads |
| LiPo 1x | 1000mAh JST-PH to J2 |
| Glass | 8mm tempered 200x300mm |
| OLED | SSD1306 1.3" 128x64 I2C 0x3C to J3 $2.80 |

## Wiring List — Net by net, what goes where

Copy this exactly. GND = solid Layer2 ground, single tie under U2 between digital GND and AGND.

### 0. ERRATA + Clock Assignment — Option B Active (from https://github.com/yaqwsx/jlcparts)
- C32346 is 32.768kHz (Epson FC-135 Q13FC13500004), NOT 32MHz — it belongs on LFCLK XL1/XL2 as Y2.
- C12674 is 8MHz HC-49S-SMD, NOT 32.768kHz — it cannot be used as nRF52840 HFXO (HFXO must be 32MHz). In Option B it is Y3 AFE4300 crystal.
- Missing 32MHz HFXO: you must order e.g. C23184 / C9005 / C13738 32MHz 12pF 3225-4P as Y1 for nRF XC1/XC2, otherwise BLE fails.
- Verified via LCSC JSON-LD scraped from product pages: C32346 -> "Crystal 32.768kHz ±20ppm 12.5pF 70kΩ SMD3215-2P" and C12674 -> "Crystal 8MHz ±20ppm 20pF HC-49S-SMD".
- FINAL Y1-Y3 MAP: Y1=32MHz -> nRF XC1/XC2, Y2=32.768kHz -> nRF XL1/XL2, Y3=8MHz -> AFE4300 XIN/XOUT with C34/C35. nRF P0.16 NC in Option B. Option A (nRF PWM 8MHz) is deprecated.

### 1. Power input USB to ETA9741 U3
- J1 VBUS (A4,A9,B4,B9) -> Net VBUS -> C23 to GND -> U3 pin20 VIN
- J1 GND (A1,A12,B1,B12) -> GND plane, 4 vias near connector
- U3 pin GND / exposed pad -> GND
- R13 CC1 from J1 A5 -> GND
- R14 CC2 from J1 B5 -> GND

### 2. Battery and charge setup U3
- J2 + (VBAT) -> Net VBAT -> C24 to GND -> U3 pin BAT, also R15 to U3 EN, also R21 top
- J2 - -> GND
- R23 ISET: U3 pin ISET -> R23 -> GND (2k = 500mA)
- U3 pin CE -> GND (charge enable low)
- U3 pin NTC -> 10k R19? Actually R19 is pullup generic - use a 10k to GND (can use R19). NTC to GND = no NTC, or repurpose R19 as 10k to GND.
- U3 pin SW (switch node) -> L1 pin1
- L1 pin2 -> Net 3V3_STAR -> C26 to GND, C27 to GND, C11 to GND
- 3V3_STAR -> Net 3V3 digital -> U1 VDD pins, J3 VDD, U4 VDD, R11,R12,R16,R17,R18,R20 pullups
- 3V3_STAR -> FB1 pin1, FB1 pin2 -> Net 3V3A -> C8,C9,C10,C19,C20,C21 to AGND and U2 AVDD pins 23,45,64

### 3. U3 enable and shipping mode
- U3 EN pin -> C30 to GND + R15 to VBAT + nRF P0.?? (say P0.02) optional override. Low = shipping <2uA. Default R15 pulls high when VBAT present.
- Hold SW1 5 seconds firmware drives EN low for shipping.

### 4. Battery measurement divider
- VBAT -> R21 1M -> Net VBAT_ADC -> R22 1M -> GND
- VBAT_ADC -> C17 to GND -> U1 pin P0.03 AIN1
- ADC = VBAT/2

### 5. Load cells W1-W4 to U2 AFE4300
- W1 RED + W2 RED + W3 RED + W4 RED tied together -> Net WS5 -> U2 pin36 WS5 + C18 4.7uF to AGND close to U2
- W1 BLACK + W2 BLACK + W3 BLACK + W4 BLACK -> Net WS6 -> U2 pin37 WS6 -> single via to GND plane under U2 (force AGND tie)
- W1 White (front left) + W3 White (rear right) tied -> Net WL_A -> R1 1k -> Node WS1_P_FILT -> C5 100nF -> Node WS1_N_FILT and C12 10nF -> AGND. WS1_P_FILT -> U2 pin30 WS1_P
- W2 White (front right) + W4 White (rear left) tied -> Net WL_B -> R2 1k -> Node WS1_N_FILT -> C13 10nF -> AGND. WS1_N_FILT -> U2 pin31 WS1_N

### 6. Electrodes E1-E4 to U2 — keep order and keep all R/C/D
For each electrode chain: PAD -> 3.3k near pad -> ESD diode to GND (via <4mm) -> 1k -> 100nF -> AFE. This gives 2 MOPPs for IEC BF.

- **E1 Left heel drive:** E1 pad -> R7 3.3k (near E1) -> Node D1_A -> D1 cathode, D1 anode -> GND, D1 via to GND <4mm -> R3 1k -> C1 100nF -> U2 pin13 I_OUTP
- **E2 Right heel drive:** E2 pad -> R8 3.3k -> Node D2_A -> D2 cathode/anode GND -> R4 1k -> C2 100nF -> U2 pin14 I_OUTN
- **E3 Left toe sense:** E3 pad -> R9 3.3k -> Node D3_A -> D3 to GND -> R5 1k -> C3 100nF -> U2 pin15 V_INP
- **E4 Right toe sense:** E4 pad -> R10 3.3k -> Node D4_A -> D4 to GND -> R6 1k -> C4 100nF -> U2 pin16 V_INN

For PESD5V0S1BL: Pin1 signal (cathode), Pin2 GND. Place GND via directly under diode.

### 7. U2 AFE4300 support caps and reference
- U2 pin11 FILT1 -> C14 10nF to AGND
- U2 pin12 FILT2 -> C15 10nF to AGND
- U2 pin18 RREF -> R24 1k 0.1% -> AGND (place <5mm to pin)
- U2 pin?? VREF? Choose: pin10 approx -> C28 2.2uF to AGND
- U2 DVDD pins (approx 62) -> C7 100nF + C22 4.7uF + C29 1uF to AGND
- U2 AVDD pins 23,45,64 -> each 100nF (C8,C9,C10) + bulk 4.7uF (C19,C20,C21) to AGND
- U2 ground pins + exposed pad -> AGND plane

### 8. Crystals — FINAL Option B assignment Y1/Y2/Y3
- Y1 32MHz (C23184 / C9005 / C13738 3225-4P 12pF): Pin1 -> U1 XC1, Pin2 -> U1 XC2, body GND. REQUIRED for BLE, internal caps. Renamed from Y_HF.
- Y2 32.768kHz (C32346 FC-135): Pin1 -> U1 XL1 P0.00, Pin2 -> U1 XL2 P0.01, body GND. RTC. Renamed from Y_LF / Y2. DNP if using internal RC (leave XL pins NC, loose timestamp 43s/day vs 1.7s) but NOT recommended for offline save feature.
- Y3 8MHz (C12674 HC-49S-SMD): Pin1 -> U2 pin58 XIN with C34 27-33pF to AGND, Pin2 -> U2 pin59 XOUT with C35 27-33pF to AGND, body to AGND. Renamed from Y_AFE / Y1. C34/C35 place <5mm to U2 pins. Tune for ~8MHz. Option B active — U1 P0.16 NC.

### 9. nRF52840 U1 to AFE4300 U2 SPI + control + clock — Option B
- U1 VDD -> C25 10uF + C6 100nF + C16 10nF to GND
- U1 GND + paddle -> GND
- SPI: U1 P0.13 SCLK -> U2 pin55 SCLK
- U1 P0.12 MOSI -> U2 pin54 SDI
- U1 P0.11 MISO <- U2 pin53 SDO
- U1 P0.14 CS with R16 10k pullup to 3V3 -> U2 pin52 CS
- U1 P0.20 DRDY with R17 10k pullup to 3V3 <- U2 pin50 DRDY, falling interrupt for weight wake
- U1 P0.22 RESET with R18 10k pullup -> U2 pin51 RESET
- AFE clock — Option B active:
  Y3 C12674 8MHz + C34/C35 27-33pF on U2 XIN pin58 / XOUT pin59. U1 P0.16 NC (no PWM). Y1 32MHz still required on U1 XC1/XC2 for BLE.
  [DEPRECATED Option A ref: nRF P0.16 PWM 8MHz was Y_AFE DNP path, now DNP Option B's opposite — if reverting to A, DNP Y3+C34+C35 and drive U1 P0.16 -> U2 XIN]

### 10. nRF to sensors I2C0
- U1 P0.30 SDA with R11 4.7k to 3V3 -> J3 SDA + U4 SDA
- U1 P0.31 SCL with R12 4.7k to 3V3 -> J3 SCL + U4 SCL
- U4 VDD -> 3V3, GND -> GND, SDO -> GND or VCC for address 0x18/0x19 (use 0x18). Via C? Add 100nF? Use C11? Actually assign: U4 VDD -> C11? Already used. Use C? Keep C11 at star still covers. Add 100nF near U4 using C?? Actually C11 already used at star - give U4 its own 100nF using C? Let's say C11 doubled as U4 decoupling, plus C?? For simplicity, C11 is star, add C? We already have 11. Keep U4 decoupling = C6? Might reuse. Better assign C11 to U4 VDD to GND.
- U4 INT1 -> U1 P0.09 wake interrupt (step >1.2g)
- J3 OLED: VDD->3V3, GND->GND, SDA,SCL as above, address 0x3C

### 11. Button and test points
- SW1 pin1 -> U1 P0.10 with R20 10k pullup to 3V3, pin2 -> GND
- Functions: low at boot = USB bootloader, 3s long press with known weight = tare calibration, 5s = shipping sleep drive EN low
- U1 P0.18 SWDIO, P0.17 SWCLK -> test pads for flashing

### 12. USB data to nRF for flashing/logs
- J1 D- (A7) -> U1 USB D- pin
- J1 D+ (A6) -> U1 USB D+ pin

### 13. Antenna
- U1 ANT pin -> C31 1pF -> L2 2.7nH -> Net ANT_TR -> C32 0.8pF to GND -> PCB inverted-F antenna trace, keepout 5mm no copper / battery / plates under
- Total length ~ 18-22mm for 2.4GHz, tune with VNA.

### 14. Grounding
- Layer2 solid GND plane
- Single AGND/DGND tie under U2 thermal pad, route all U2 GNDs to AGND island tied at one point to main GND via 0 ohm or star.
- FB1 isolates 3V3A from 3V3, place near U2.
- 4 vias for USB GND, 2 vias for battery GND, 8+ vias for GND stitching.

## Notes on what NOT to remove for battery / accuracy / safety
- Keep D1-D4 PESD5V0S1BL low-C ESD - SM4007PL/M7 = 1000V Vc kills chip, 15-20pF errors BIA. SMBJ/PSM712 = Vc 10-19V too high and Cj 800-3000pF shorts body at 50kHz.
- Keep C1-C4 100nF blocking + R7-R10 3.3k + R3-R6 1k for IEC 500uA safety limit
- Keep R24 1k 0.1% reference for BFP accuracy
- Keep FB1 for accuracy, L1 for power, R21+R22 divider for battery reading
- Clocks FINAL Y1/Y2/Y3 Option B: Y1 must be 32MHz (C23184) on nRF XC1/XC2 for BLE - cannot use C12674 8MHz on XC1/XC2. Y2 C32346 32.768kHz on XL1/XL2 for RTC/offline timestamps - technically DNP-able for RC (+/- 43s/day vs 1.7s) but keep for offline save requirement. Y3 C12674 8MHz on AFE XIN/XOUT with C34/C35 27-33pF mandatory in Option B, nRF P0.16 NC. [If reverting to Option A: DNP Y3/C34/C35 and drive nRF P0.16 PWM 8MHz -> U2 XIN]

## Bring-up order — Option B Y1/Y2/Y3
1. Solder U3, L1, C23-C27, C11, verify 3V3 = 3.3V from USB
2. Solder U1 + Y1 32MHz (C23184 etc) on XC1/XC2 - note C12674 8MHz is Y3, NOT for XC1/XC2 - + Y2 C32346 32.768kHz on XL1/XL2, flash blinky, check USB enumeration and BLE advertising 1Hz. If you put Y3 8MHz on XC1/XC2, BLE WILL NOT start — HFXO must be 32MHz.
3. Solder U2 + Y3 C12674 8MHz on XIN pin58/XOUT pin59 + C34/C35 27-33pF to AGND, verify 8MHz on XOUT with scope probe x10 (expect ~0.8Vpp). R24, C28, C14-C15, R1-R2/C5-C12-C13, load cells, check weight raw ~5uV steps. U1 P0.16 NC per Option B.
4. Solder E1-E4 path R7-R10, D1-D4, R3-R6, C1-C4, test BIA with known 500ohm resistor between heel-to-toe = ~0.3V @ 50kHz expected
5. Solder U4, J3 OLED, check step wake
6. Full system tare and BIA cal with 1k ref and body measurements.
