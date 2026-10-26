# Modern Apps
This is a collection of apps that aims to replace the ecosystem feel of proprietary ecosystems like Apple and Google, but with 100% open-source apps.

Unlike other open-source ecosystems, all apps in Modern Apps are built using modern technologies: Jetpack Compose, Material 3 You, etc, which makes them "look nice" and easy to read for those who wish to audit the code.

I also highly encourage people to read the code of these apps, even if you are just a beginner in Kotlin / Android app dev. All apps are structured to be as beginner friendly and readable as possible. It's good to understand the apps that are running on your phone.

This ecosystem also contains apps that aren't as well covered by the open-source ecosystem - particularly Find Family (a location sharing app), YouPipe (a privacy-based youtube frontend), Email (an email client that supports push notifications and a functional dark mode widget), the upcoming Maps (which supports public transit routing, a feature missing from existing alternatives), and the upcoming Communicate (an aggregator for other messaging apps).

This ecosystem also includes games, which I personally believe are an important part of a good phone experience (at least the kind from the "golden age" of mobile games are), so I have remade some mobile games I enjoyed and might add more as time goes on.

Important note: Do not confuse ecosystem with walled garden. There are benefits to adopting the whole ecosystem, but they are quality of life improvements, which while nice, are easy to live without if you so choose. Feel free to use as many or as few apps from this repo as you want.

### My Motivations
**I personally use every app here.** My motivation for creating these apps is because I want to use them. I share them because it's the right thing to do, and because collaboration will only help bring this large project to fruition.

### History of Modern-Apps (and Vayun Mathur)
The history of Modern-Apps goes long before this repository was created. 3 years ago, I switched from iOS to GrapheneOS out of a simple desire to control the technology I used. I taught myself Android app development, to try and build my own apps, giving birth to Material-Suite: https://github.com/vayun-mathur/Material-Suite. Though I learned a lot doing it, Material-Suite would end up going nowhere, and I switched back to using apps built by others.

My interest in building my own apps was reborn just over a year ago, when I learned about the privacy practices of Life360. Unfortunely for me, at that time, there were no privacy respecting alternatives with the same features. So over the next year, I built one myself: FindFamily (originally here: https://github.com/vayun-mathur/Find-Family). Though it took a lot of time and effort, eventually, I was able to build the first (and to my knowledge only) free and open source, end-to-end-encrypted, cross platform, location sharing app.

Motivated by the success of FindFamily, I started to build more apps ([Contacts](https://github.com/vayun-mathur/Contacts), [OpenAssistant](https://github.com/vayun-mathur/OpenAssistant), [PDF](https://github.com/vayun-mathur/PDF), and more). After several months of developing these applications independently of each other, I decided to build them together with a consistent design scheme and common components to get back that ecosystem feel that I had back when I was on iOS, so I moved all their code to this repo and the rest is history!

### Certificate
All APK files made by me will match this SHA-256 certificate:
`17:6F:CB:25:25:57:3E:5B:E8:E1:CB:3A:49:6D:D9:7B:13:7E:81:CA:5B:88:7A:1D:32:CB:89:4B:4E:57:17:B4`

### Acknowledgement of Bugs
The v2.x.x versions of these apps are **all to be considered beta versions**. Lots of features/apps being done in a short period of time will inevitably result in some bugs here and there. All bug reports are appreciated, and any PRs to add fixes or new features (as long as not bloaty) are very appreciated. Version numbering will change to v3.x.x when all the apps are stable.

# Master Table:

Every released and planned app is listed here, with its subgroup:

- **MA-1** are the flagship apps - either fully unique in FOSS or particulary noteworthy
- **MA-2** is the rest of a typical ecosystem: the standard stuff.
- **MA-3** is niche stuff that a small portion of people may want to use occasionally.
- **MA-E** is real-world stuff
- **MA-G** is games.
- **MA-C** are apps built for the MA-OS project, that may or may not also be available as user apps

Though some apps may have exceptions, generally a 64-bit device running at least Android 12 is required.

**Cast (Android TV receiver)** is the one app that is not for a phone: it runs on an Android TV or Google TV and is what the Cast app mirrors to. It is listed in the table below like any other app, but it will not appear on a phone's launcher and is not installable on one.

**THE RECOMMENDED INSTALLATION METHOD IS MA APP STORE.** It is the most secure way to download MA apps.

**FOR F-DROID USERS, ADD THIS REPO TO YOUR CLIENT: [https://maproj.org/fdroid](https://maproj.org/fdroid)** (for faster updates and apps unavailable on the main repo)

## Apps

| App Name | Group | Homepage | F-Droid | Obtainium |
| :--- | :---: | :---: | :---: | :---: |
| **App Store** | MA-C | [Homepage](https://maproj.org/apps/appstore) | [![MA Repo](https://img.shields.io/badge/MA_Repo-Download-green?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.appstore/) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.maproj.org/obtainium-link?name=App%20Store&packageName=com.vayunmathur.appstore&apk=appstore-release.apk) |
| **Astronomy** | MA-3 | [Homepage](https://maproj.org/apps/astronomy) | [![MA Repo](https://img.shields.io/badge/MA_Repo-Download-green?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.astronomy/) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.maproj.org/obtainium-link?name=Astronomy&packageName=com.vayunmathur.astronomy&apk=astronomy-release.apk) |
| **Auto** | MA-C | [Homepage](https://maproj.org/apps/auto) | In Progress | In Progress |
| **Calculator** | MA-2 | [Homepage](https://maproj.org/apps/calculator) | [![MA Repo](https://img.shields.io/badge/MA_Repo-Download-green?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.calculator/) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.maproj.org/obtainium-link?name=Calculator&packageName=com.vayunmathur.calculator&apk=calculator-release.apk) |
| **Calendar** | MA-2 | [Homepage](https://maproj.org/apps/calendar) | [![MA Repo](https://img.shields.io/badge/MA_Repo-Download-green?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.calendar/) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.maproj.org/obtainium-link?name=Calendar&packageName=com.vayunmathur.calendar&apk=calendar-release.apk) |
| **Camera** | MA-1 | [Homepage](https://maproj.org/apps/camera) | [![MA Repo](https://img.shields.io/badge/MA_Repo-Download-green?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.camera/) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.maproj.org/obtainium-link?name=Camera&packageName=com.vayunmathur.camera&apk=camera-release.apk) |
| **Cast** | MA-C | [Homepage](https://maproj.org/apps/cast) | [![MA Repo](https://img.shields.io/badge/MA_Repo-Download-green?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.cast/) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.maproj.org/obtainium-link?name=Cast&packageName=com.vayunmathur.cast&apk=cast-release.apk) |
| **Cast (Android TV receiver)** | N/A | [Homepage](https://maproj.org/apps/cast-tv) | [![MA Repo](https://img.shields.io/badge/MA_Repo-Download-green?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.cast.tv/) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.maproj.org/obtainium-link?name=MA%20Cast&packageName=com.vayunmathur.cast.tv&apk=tv-release.apk) |
| **Clock** | MA-2 | [Homepage](https://maproj.org/apps/clock) | [![MA Repo](https://img.shields.io/badge/MA_Repo-Download-green?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.clock/) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.maproj.org/obtainium-link?name=Clock&packageName=com.vayunmathur.clock&apk=clock-release.apk) |
| **Code** | MA-3 | [Homepage](https://maproj.org/apps/code) | [![MA Repo](https://img.shields.io/badge/MA_Repo-Download-green?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.code/) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.maproj.org/obtainium-link?name=Code&packageName=com.vayunmathur.code&apk=code-release.apk) |
| **Communicate** | MA-1 | [Homepage](https://maproj.org/apps/communicate) | In Progress | In Progress |
| **Contacts** | MA-2 | [Homepage](https://maproj.org/apps/contacts) | [![MA Repo](https://img.shields.io/badge/MA_Repo-Download-green?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.contacts/) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.maproj.org/obtainium-link?name=Contacts&packageName=com.vayunmathur.contacts&apk=contacts-release.apk) |
| **Education** | MA-3 | [Homepage](https://maproj.org/apps/education) | In Progress | In Progress |
| **Email** | MA-1 | [Homepage](https://maproj.org/apps/email) | [![F-Droid](https://img.shields.io/badge/F--Droid-Download-blue?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.email) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.maproj.org/obtainium-link?name=Email&packageName=com.vayunmathur.email&apk=email-release.apk) |
| **EverySync** | MA-2 | [Homepage](https://maproj.org/apps/everysync) | In Progress | In Progress |
| **Files** | MA-2 | [Homepage](https://maproj.org/apps/files) | [![MA Repo](https://img.shields.io/badge/MA_Repo-Download-green?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.files/) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.maproj.org/obtainium-link?name=Files&packageName=com.vayunmathur.files&apk=files-release.apk) |
| **Find Family** | MA-1 | [Homepage](https://maproj.org/apps/findfamily) | [![F-Droid](https://img.shields.io/badge/F--Droid-Download-blue?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.findfamily) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.maproj.org/obtainium-link?name=Find%20Family&packageName=com.vayunmathur.findfamily&apk=findfamily-release.apk) |
| **Flashcards** | MA-3 | [Homepage](https://maproj.org/apps/flashcards) | [![MA Repo](https://img.shields.io/badge/MA_Repo-Download-green?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.flashcards/) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.maproj.org/obtainium-link?name=Flashcards&packageName=com.vayunmathur.flashcards&apk=flashcards-release.apk) |
| **Food Delivery (US only)** | MA-E | [Homepage](https://maproj.org/apps/fooddelivery) | [![MA Repo](https://img.shields.io/badge/MA_Repo-Download-green?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.fooddelivery/) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.maproj.org/obtainium-link?name=Food%20Delivery&packageName=com.vayunmathur.fooddelivery&apk=fooddelivery-release.apk) |
| **Global eSIM** | MA-E | [Homepage](https://maproj.org/apps/globalesim) | In Progress | In Progress |
| **Health** | MA-2 | [Homepage](https://maproj.org/apps/health) | [![MA Repo](https://img.shields.io/badge/MA_Repo-Download-green?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.health/) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.maproj.org/obtainium-link?name=Health&packageName=com.vayunmathur.health&apk=health-release.apk) |
| **Keyboard** | MA-2 | [Homepage](https://maproj.org/apps/keyboard) | [![MA Repo](https://img.shields.io/badge/MA_Repo-Download-green?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.keyboard/) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.maproj.org/obtainium-link?name=Keyboard&packageName=com.vayunmathur.keyboard&apk=keyboard-release.apk) |
| **Launcher** | MA-C | [Homepage](https://maproj.org/apps/launcher) | [![MA Repo](https://img.shields.io/badge/MA_Repo-Download-green?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.launcher/) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.maproj.org/obtainium-link?name=Launcher&packageName=com.vayunmathur.launcher&apk=launcher-release.apk) |
| **Maps** | MA-1 | [Homepage](https://maproj.org/apps/maps) | In Progress | In Progress |
| **Measure** | MA-3 | [Homepage](https://maproj.org/apps/measure) | [![MA Repo](https://img.shields.io/badge/MA_Repo-Download-green?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.measure/) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.maproj.org/obtainium-link?name=Measure&packageName=com.vayunmathur.measure&apk=measure-release.apk) |
| **Music** | MA-2 | [Homepage](https://maproj.org/apps/music) | [![MA Repo](https://img.shields.io/badge/MA_Repo-Download-green?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.music/) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.maproj.org/obtainium-link?name=Music&packageName=com.vayunmathur.music&apk=music-release.apk) |
| **Notes** | MA-2 | [Homepage](https://maproj.org/apps/notes) | [![MA Repo](https://img.shields.io/badge/MA_Repo-Download-green?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.notes/) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.maproj.org/obtainium-link?name=Notes&packageName=com.vayunmathur.notes&apk=notes-release.apk) |
| **Office** | MA-1 | [Homepage](https://maproj.org/apps/office) | [![F-Droid](https://img.shields.io/badge/F--Droid-Download-blue?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.office) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.maproj.org/obtainium-link?name=Office&packageName=com.vayunmathur.office&apk=office-release.apk) |
| **Open Assistant** | MA-1 | [Homepage](https://maproj.org/apps/openassistant) | [![F-Droid](https://img.shields.io/badge/F--Droid-Download-blue?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.openassistant) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.maproj.org/obtainium-link?name=OpenAssistant&packageName=com.vayunmathur.openassistant&apk=openassistant-release.apk) |
| **Passwords** | MA-2 | [Homepage](https://maproj.org/apps/passwords) | [![MA Repo](https://img.shields.io/badge/MA_Repo-Download-green?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.passwords/) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.maproj.org/obtainium-link?name=Passwords&packageName=com.vayunmathur.passwords&apk=passwords-release.apk) |
| **PDF Reader** | MA-1 | [Homepage](https://maproj.org/apps/pdf) | [![F-Droid](https://img.shields.io/badge/F--Droid-Download-blue?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.pdf) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.maproj.org/obtainium-link?name=PDF&packageName=com.vayunmathur.pdf&apk=pdf-release.apk) |
| **Photos** | MA-1 | [Homepage](https://maproj.org/apps/photos) | [![F-Droid](https://img.shields.io/badge/F--Droid-Download-blue?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.photos) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.maproj.org/obtainium-link?name=Photos&packageName=com.vayunmathur.photos&apk=photos-release.apk) |
| **Share** | MA-2 | [Homepage](https://maproj.org/apps/share) | [![MA Repo](https://img.shields.io/badge/MA_Repo-Download-green?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.share/) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.maproj.org/obtainium-link?name=Share&packageName=com.vayunmathur.share&apk=share-release.apk) |
| **Shop** | MA-E | [Homepage](https://maproj.org/apps/shop) | In Progress | In Progress |
| **SecureMail** | MA-1 | [Homepage](https://maproj.org/apps/securemail) | In Progress | In Progress |
| **Speech Recognizer** | MA-2 | [Homepage](https://maproj.org/apps/speech) | [![MA Repo](https://img.shields.io/badge/MA_Repo-Download-green?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.speech/) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.maproj.org/obtainium-link?name=Speech%20Recognizer&packageName=com.vayunmathur.speech&apk=speech-release.apk) |
| **Taxi (US and Canada only)** | MA-E | [Homepage](https://maproj.org/apps/taxi) | [![MA Repo](https://img.shields.io/badge/MA_Repo-Download-green?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.taxi/) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.maproj.org/obtainium-link?name=Taxi&packageName=com.vayunmathur.taxi&apk=taxi-release.apk) |
| **Things** | MA-3 | [Homepage](https://maproj.org/apps/things) | In Progress | In Progress |
| **Todo** | MA-2 | [Homepage](https://maproj.org/apps/todo) | In Progress | In Progress |
| **Translate** | MA-2 | [Homepage](https://maproj.org/apps/translate) | [![MA Repo](https://img.shields.io/badge/MA_Repo-Download-green?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.translate/) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.maproj.org/obtainium-link?name=Translate&packageName=com.vayunmathur.translate&apk=translate-release.apk) |
| **Travel** | MA-E | [Homepage](https://maproj.org/apps/travel) | In Progress | In Progress |
| **Tuner** | MA-3 | [Homepage](https://maproj.org/apps/tuner) | In Progress | In Progress |
| **Uniflix** | MA-1 | [Homepage](https://maproj.org/apps/uniflix) | In Progress | In Progress |
| **VPN** | MA-2 | [Homepage](https://maproj.org/apps/vpn) | [![MA Repo](https://img.shields.io/badge/MA_Repo-Download-green?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.vpn/) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.maproj.org/obtainium-link?name=VPN&packageName=com.vayunmathur.vpn&apk=vpn-release.apk) |
| **Watch** | MA-2 | [Homepage](https://maproj.org/apps/watch) | In Progress | In Progress |
| **Weather** | MA-2 | [Homepage](https://maproj.org/apps/weather) | [![F-Droid](https://img.shields.io/badge/F--Droid-Download-blue?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.weather) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.maproj.org/obtainium-link?name=Weather&packageName=com.vayunmathur.weather&apk=weather-release.apk) |
| **Web** | MA-2 | [Homepage](https://maproj.org/apps/web) | [![MA Repo](https://img.shields.io/badge/MA_Repo-Download-green?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.web/) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.maproj.org/obtainium-link?name=Web&packageName=com.vayunmathur.web&apk=web-release.apk) |
| **YouPipe** | MA-1 | [Homepage](https://maproj.org/apps/youpipe) | [![F-Droid](https://img.shields.io/badge/F--Droid-Download-blue?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.youpipe) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.maproj.org/obtainium-link?name=YouPipe&packageName=com.vayunmathur.youpipe&apk=youpipe-release.apk) |

## Games

| App Name | Group | Homepage | F-Droid | Obtainium |
| :--- | :---: | :---: | :---: | :---: |
| **Games Hub** | MA-G | [Homepage](https://maproj.org/apps/games-hub) | [![MA Repo](https://img.shields.io/badge/MA_Repo-Download-green?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.games.hub/) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.maproj.org/obtainium-link?name=Games%20Hub&packageName=com.vayunmathur.games.hub&apk=hub-release.apk) |
| **Arrows** | MA-G | [Homepage](https://maproj.org/apps/games-arrows) | [![MA Repo](https://img.shields.io/badge/MA_Repo-Download-green?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.games.arrows/) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.maproj.org/obtainium-link?name=Arrows&packageName=com.vayunmathur.games.arrows&apk=arrows-release.apk) |
| **Chess** | MA-G | [Homepage](https://maproj.org/apps/games-chess) | [![MA Repo](https://img.shields.io/badge/MA_Repo-Download-green?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.games.chess/) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.maproj.org/obtainium-link?name=Chess&packageName=com.vayunmathur.games.chess&apk=chess-release.apk) |
| **Kart** | MA-G | [Homepage](https://maproj.org/apps/games-kart) | In Progress | In Progress |
| **Logic Gate** | MA-G | [Homepage](https://maproj.org/apps/games-logicgate) | In Progress | In Progress |
| **Minesweeper** | MA-G | [Homepage](https://maproj.org/apps/games-minesweeper) | [![MA Repo](https://img.shields.io/badge/MA_Repo-Download-green?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.games.minesweeper/) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.maproj.org/obtainium-link?name=Minesweeper&packageName=com.vayunmathur.games.minesweeper&apk=minesweeper-release.apk) |
| **Nonogram** | MA-G | [Homepage](https://maproj.org/apps/games-nonogram) | [![MA Repo](https://img.shields.io/badge/MA_Repo-Download-green?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.games.nonogram/) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.maproj.org/obtainium-link?name=Nonogram&packageName=com.vayunmathur.games.nonogram&apk=nonogram-release.apk) |
| **Pipes** | MA-G | [Homepage](https://maproj.org/apps/games-pipes) | [![MA Repo](https://img.shields.io/badge/MA_Repo-Download-green?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.games.pipes/) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.maproj.org/obtainium-link?name=Pipes&packageName=com.vayunmathur.games.pipes&apk=pipes-release.apk) |
| **Solitaire** | MA-G | [Homepage](https://maproj.org/apps/games-solitaire) | [![MA Repo](https://img.shields.io/badge/MA_Repo-Download-green?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.games.solitaire/) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.maproj.org/obtainium-link?name=Solitaire&packageName=com.vayunmathur.games.solitaire&apk=solitaire-release.apk) |
| **Sudoku** | MA-G | [Homepage](https://maproj.org/apps/games-sudoku) | [![MA Repo](https://img.shields.io/badge/MA_Repo-Download-green?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.games.sudoku/) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.maproj.org/obtainium-link?name=Sudoku&packageName=com.vayunmathur.games.sudoku&apk=sudoku-release.apk) |
| **Unblock Jam** | MA-G | [Homepage](https://maproj.org/apps/games-unblockjam) | [![MA Repo](https://img.shields.io/badge/MA_Repo-Download-green?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.games.unblockjam/) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.maproj.org/obtainium-link?name=Unblock%20Jam&packageName=com.vayunmathur.games.unblockjam&apk=unblockjam-release.apk) |
| **Voxels** | MA-G | [Homepage](https://maproj.org/apps/games-voxels) | In Progress | In Progress |
| **Word Maker** | MA-G | [Homepage](https://maproj.org/apps/games-wordmaker) | [![MA Repo](https://img.shields.io/badge/MA_Repo-Download-green?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.games.wordmaker/) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.maproj.org/obtainium-link?name=Word%20Maker&packageName=com.vayunmathur.games.wordmaker&apk=wordmaker-release.apk) |

## MAOS System Apps

These MA-C apps are part of the MA-OS project and cannot be used outside of MAOS:

| App Name | Group | Homepage |
| :--- | :---: | :---: |
| **Backup** | MA-C | [Homepage](https://maproj.org/apps/backup) |
| **Emergency** | MA-C | [Homepage](https://maproj.org/apps/emergency) |
| **eUICC** | MA-C | [Homepage](https://maproj.org/apps/euicc) |
| **Log Viewer** | MA-C | [Homepage](https://maproj.org/apps/logviewer) |
| **Network Location** | MA-C | [Homepage](https://maproj.org/apps/networklocation) |
| **Parental Controls** | MA-C | [Homepage](https://maproj.org/apps/parentalcontrols) |
| **Screen Time** | MA-C | [Homepage](https://maproj.org/apps/screentime) |
| **Setup Wizard** | MA-C | [Homepage](https://maproj.org/apps/setupwizard) |
| **Updater** | MA-C | [Homepage](https://maproj.org/apps/updater) |

# Contributions

## Translations
Contribute to translations for these apps through the weblate project: https://hosted.weblate.org/projects/modern-apps/

# App Feature Descriptions

Each app has its own homepage describing its features and relevant technical details. See the **Homepage** links in the download tables above, or browse them all at [maproj.org/apps](https://maproj.org/apps).
