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
nRF makes 8MHz clock for AFE4300, PCB antenna to phone
```

## Bill of Materials — LCSC codes and single quantity price

Prices from JLC parts database 2026-07-22.

### Main chips

| Ref | LCSC | MPN | Qty | Price | Purpose |
|---|---|---|---|---|---|
| U2 | C528638 | AFE4300PNR | 1 | $6.8996 | This chip measures both weight and body fat. It provides a stable voltage to the load cells, reads the tiny voltage from the weight bridge for weight, creates a small alternating current that flows between the feet, and measures the returning voltage to compute body impedance. |
| U1 | C190794 | NRF52840-QIAA-R | 1 | $5.7803 | This is the main microcontroller that runs the program and talks to the phone over Bluetooth Low Energy. It advertises once per second so the phone can wake it later, controls the screen over I2C, reads the accelerometer, stores past weigh ins to flash memory, and provides USB for flashing. |
| U3 | C7465513 | ETA9741E8A | 1 | $0.1586 | This is the only power management chip. It takes 5 volt from USB, charges the lithium battery at 500 milliamp, makes a stable 3.3 volt supply that can handle 1.5 amp peaks for the radio, and protects the battery from over voltage and over current, and has a shipping sleep mode under 2 microamp. |
| U4 | C110926 | LIS2DH12TR | 1 | $0.8395 | This accelerometer detects when someone steps on the scale. When acceleration exceeds 1.2 times gravity it sends an interrupt to wake the main chip immediately so the weight can be measured right away. |

### Electrode protection

| Ref | LCSC | MPN | Qty | Price | Purpose |
|---|---|---|---|---|---|
| D-ESD | C84374 | PESD5V0S1BL,315 | 4 | $0.0227 each | This diode protects the measurement chip from static electricity when a foot touches the plates. It has very low capacitance so it does not disturb the body fat measurement, and it shunts a static zap to ground before it can damage the chip. |

### Clocks and magnetics

| Ref | LCSC | MPN | Qty | Price | Purpose |
|---|---|---|---|---|---|
| Y-32M | C32346 | Q13FC13500004 32MHz | 1 | $0.1744 | This crystal gives the accurate 32 megahertz clock that the Bluetooth radio needs to stay on frequency. |
| Y-32k | C12674 | 32.768kHz crystal | 1 | $0.0995 | This crystal keeps the real time clock running while the board sleeps. It gives timestamps to stored weigh ins and times the one second Bluetooth advertising interval. |
| L1 | C5832372 | FTC252012S2R2MBCA 2.2uH | 1 | $0.0341 | This inductor stores energy for the switching regulator inside the ETA9741 chip. It must handle high current during Bluetooth transmission. No base category inductor exists with high enough saturation current in the JLC catalog. |
| FB1 | C1002 | GZ1608D601TF ferrite bead | 1 | $0.0189 | This ferrite bead filters noise and separates noisy digital power from clean analog power going to the weight and body fat chip. |

### Connectors

| Ref | LCSC | MPN | Qty | Price | Purpose |
|---|---|---|---|---|---|
| J-USB | C165948 | TYPE-C-31-M-12 Type-C | 1 | $0.1858 | This connector brings in 5 volt from a USB cable for charging and provides the data lines to the main chip for flashing firmware and logs. |

Load cell wires are soldered directly to board pads with glue for strain relief to avoid a connector.

### Capacitors — Base free

| LCSC | Value | Qty | Price | Purpose |
|---|---|---|---|---|
| C307331 | 100nF 50V 0402 | 11 | $0.0076 | Four block direct current so only alternating current can enter the body on each electrode for safety. Seven are placed close to chip power pins to filter power supply noise and keep the voltage stable. |
| C15195 | 10nF 50V 0402 | 6 | $0.0035 | Two filter common mode noise on the weight inputs, two filter the internal analog filter pins on the body fat chip, and two are extra decoupling capacitors near chips. |
| C23733 | 4.7uF 10V 0402 | 5 | $0.0192 | These hold charge near the analog supplies of the measurement chip and at the power chip output to keep voltage from dropping during Bluetooth transmission. |
| C15525 | 10uF 6.3V 0402 | 3 | $0.0199 | These are bulk capacitors at the power chip input from USB, at the battery pin, and at the microcontroller power pin to smooth the supply. |
| C59461 | 22uF 10V 0603 | 2 | $0.0238 | These large capacitors at the 3.3 volt output handle fast load changes when the radio turns on and transmits. |
| C12530 | 2.2uF 10V 0402 | 1 | $0.0078 | This capacitor stabilizes the internal voltage reference inside the measurement chip so body fat readings stay steady. |
| C52923 | 1uF 16V 0402 | 2 | $0.0143 | One decouples the digital supply of the measurement chip and one forms a delay on the enable pin so the chip powers up in the correct order. |

### Resistors — Base free except reference

| LCSC | Value | Qty | Price | Purpose |
|---|---|---|---|---|
| C11702 | 1k 1% 0402 | 6 | $0.0011 | Two limit current and form a low pass filter with capacitors on the weight inputs. Four are second stage safety resistors after the protection diodes on each electrode to further limit current into the body. |
| C22978 | 3.3k 1% 0603 | 4 | $0.0019 | First stage safety resistors placed close to each electrode entry pad. Together with the 1k resistor they keep body current under 500 microamp even if the drive pin shorts, which meets the safety limit for body contact. |
| C25900 | 4.7k 0402 | 2 | $0.0011 | These pull the data line and clock line of the two wire bus high so the screen and accelerometer can communicate with the main chip. |
| C25905 | 5.1k 0402 | 2 | $0.0009 | These pull the USB Type C configuration pins to ground to tell the charger to provide 5 volt at 500 milliamp. |
| C25744 | 10k 0402 | 6 | $0.0029 | These hold chip select, data ready, reset, bootloader, enable, and button lines high when not being driven so the chips start in a safe state. |
| C26083 | 1M 0402 | 2 | $0.0011 | Two form a voltage divider that reduces battery voltage so the microcontroller can measure battery percent with its analog to digital converter. |
| C4109 | 2k 0402 | 1 | $0.0008 | This resistor sets the lithium battery charge current to 500 milliamp on the power management chip. |
| C852624 | 1k 0.1% 0402 | 1 | $0.0246 | This precise resistor is the calibration reference for body impedance. Its exact value defines all body fat calculations, so it must be high accuracy. A 1 percent error would shift body fat by about 1 percent. |

### Switches

| LCSC | MPN | Qty | Price | Purpose |
|---|---|---|---|---|
| C318884 | tactile switch 5.1mm | 1 | $0.0197 | This button allows bootloader mode when held low at power on for flashing, tare calibration when long pressed 3 seconds with a known weight, and shipping sleep when held 5 seconds to turn off power. It is base category free per your screenshot. |

### Mechanical and display — external, not JLC assembly

| Ref | Qty | Purpose |
|---|---|---|
| Load cells 50kg 3 wire Red Black White | 4 | Metal beams with strain gauges that change resistance under body weight. Four form one full bridge for weight with good rejection of off center loading. |
| Stainless plates 304 50x60mm | 4 | Metal electrodes that touch bare feet. Heel plates drive current, toe plates sense voltage. Gap 5mm per foot avoids direct shorting between drive and sense. |
| Pogo pins 6mm pads | 4 | Spring contacts that connect the board to the stainless plates through the glass top. |
| LiPo 1000mAh JST-PH connector | 1 | Rechargeable battery that powers the board when not plugged into USB. |
| Glass top 8mm tempered 200x300mm | 1 | Structural top with printed isolation to separate electrodes and provide wipeable surface. |
| OLED SSD1306 1.3 inch 128x64 I2C 0x3C | 1 | External display module about $2.80. Shows weight, body fat percent, battery bar, Bluetooth icon, and charging lightning. Replaces discrete LED and buzzer with text and icons. |

## Wiring

### Load cells to AFE4300

Red wires of four cells tied to WS5 pin36 plus 4.7 microfarad to analog ground. Black wires tied to WS6 pin37 to ground plane with single via near chip. Front left white plus rear right white to 1k resistor plus 100 nanofarad differential capacitor to A- plus 10 nanofarad to ground to WS1_P pin30. Front right white plus rear left white to 1k plus 10 nanofarad to ground to WS1_N pin31.

### Electrodes to AFE4300

Plate to pogo pad to 3.3 kiloohm resistor near entry to protection diode to ground via under 4 millimeters to 1 kiloohm resistor to 100 nanofarad blocking capacitor to chip. Left heel drive to I_OUTP pin13, right heel drive to I_OUTN pin14 for current drive. Left toe sense to V_INP pin15, right toe sense to V_INN pin16 for voltage sense. Filter pins 11 and 12 each 10 nanofarad to ground. Reference pin18 1 kiloohm 0.1 percent to ground close to chip.

### nRF52840 to AFE4300 and sensors

High voltage supply pin to 3.3 volt from power chip, digital power pins each 100 nanofarad plus 4.7 microfarad bulk. Programming pins SWDIO P0.18 and SWCLK P0.17. Crystals 32 megahertz on XC1 XC2 and 32.768 kilohertz on XL1 XL2 for real time clock. SPI 1 megahertz: clock P0.13 to pin55, master out P0.12 to pin54, master in P0.11 from pin53, chip select P0.14 pin52 with 10 kiloohm pull up, data ready P0.20 pin50 with pull up as falling interrupt, reset P0.22 pin51 with pull up. I2C0: data P0.30 with 4.7 kiloohm pull up and clock P0.31 with pull up to OLED address 0x3C and accelerometer address 0x18 interrupt to P0.09. P0.09 wakes on step over 1.2 times gravity. Button on P0.10 with pull up for tare and bootloader. P0.03 analog reads battery divider plus capacitor. USB Type C voltage bus to power chip input 10 microfarad, configuration pins 5.1 kiloohm to ground, data minus to USB D- pin, data plus to USB D+ pin. Antenna radio pin to matching network of 1 picofarad capacitor plus 2.7 nanohenry inductor plus 0.8 picofarad capacitor to PCB trace antenna with keepout 5 millimeter with no battery or plates under it. Main chip generates 8 megahertz square wave on a pin to AFE XIN pin58, XOUT not connected. Bluetooth advertises once per second about 15 microamp average and is connectable for phone wake.

### Power ETA9741

5 volt from USB to input pin20 with 10 microfarad. Battery pin to JST lithium battery plus 10 microfarad with internal protection over voltage 4.43 volt over current 3 amp. Switch pin to inductor L1 2.2 microhenry to output node 3.3 volt fixed with two 22 microfarad plus 100 nanofarad at star. Output to digital 3.3 volt to nRF plus ferrite bead to analog 3.3 volt to AFE analog supplies pins 23, 45, 64. Enable to general pin plus 10 kiloohm pull up to battery, low equals shipping sleep under 2 microamp. Charge enable to ground, ISET 2 kiloohm equals 500 milliamp charge, temperature pin 10 kiloohm to ground. Battery percent read via divider. Ground four layer with solid ground on layer two and single analog digital tie under measurement chip.
