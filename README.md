# Modern Apps
This is a collection of apps that aims to replace the ecosystem feel of proprietary ecosystems like Apple and Google, but with 100% open-source apps.

Unlike other open-source ecosystems, all apps in Modern Apps are built using modern technologies: Jetpack Compose, Material 3 You, etc, which makes them "look nice" and easy to read for those who wish to audit the code.

I also highly encourage people to read the code of these apps, even if you are just a beginner in Kotlin / Android app dev. All apps are structured to be as beginner friendly and readable as possible. It's good to understand the apps that are running on your phone.

This ecosystem also contains apps that aren't as well covered by the open-source ecosystem - particularly Find Family (a location sharing app), YouPipe (a privacy-based youtube frontend), Email (an email client that supports push notifications and a functional dark mode widget), the upcoming Maps (which supports public transit routing, a feature missing from existing alternatives), and the upcoming Messages (a aggregator for other messaging apps).

This ecosystem also includes games, which I personally believe are an important part of a good phone experience (at least the kind from the "golden age" of mobile games are), so I have remade some mobile games I enjoyed and might add more as time goes on.

### My Motivations
**I personally use every app here.** My motivation for creating these apps is because I want to use them. I share them because it's the right thing to do, and because collaboration will only help bring this large project to fruition.

### History of Modern-Apps (and Vayun Mathur)
The history of Modern-Apps goes long before this repository was created. 3 years ago, I switched from iOS to GrapheneOS out of a simple desire to control the technology I used. Out of this desire, I taught myself Android app development, to try and build my own apps, giving birth to Material-Suite: https://github.com/vayun-mathur/Material-Suite. Though I learned a lot doing it, Material-Suite would end up going nowhere, and I switched back to using apps built by others.

My interest in building my own apps was reborn just over a year ago, when I learned about the privacy practices of Life360. Unfortunely for me, at that time, there were no privacy respecting alternatives with the same features. So over the next year, I built one myself: FindFamily (originally here: https://github.com/vayun-mathur/Find-Family). Though it took a lot of time and effort, eventually, I was able to build the first (and to my knowledge only) free and open source, end-to-end-encrypted, cross platform, location sharing app.

Motivated by the success of FindFamily, I started to build more apps ([Contacts](https://github.com/vayun-mathur/Contacts), [OpenAssistant](https://github.com/vayun-mathur/OpenAssistant), [PDF](https://github.com/vayun-mathur/PDF), and more). After several months of developing these applications independently of each other, I decided to build them together with a consistent design scheme and common components to get back that ecosystem feel that I had back when I was on iOS, so I moved all their code to this repo and the rest is history!

### Certificate
All APK files made by me will match this SHA-256 certificate:
`17:6F:CB:25:25:57:3E:5B:E8:E1:CB:3A:49:6D:D9:7B:13:7E:81:CA:5B:88:7A:1D:32:CB:89:4B:4E:57:17:B4`

### Acknowledgement of Bugs
The v2.x.x versions of these apps are all to be considered beta versions. Lots of features/apps being done in a short period of time will inevitably result in some bugs here and there. All bug reports are appreciated, and any PRs to add fixes or new features (as long as not bloaty) are very appreciated. Version numbering will change to v3.x.x when all the apps are stable.

# Download links:

Every released and planned app is listed here:

Though some apps may have exceptions, generally a 64-bit device running at least Android 12 is required.

| App Name | F-Droid | Obtainium |
| :--- | :---: | :---: |
| **Calendar** | [![F-Droid](https://img.shields.io/badge/F--Droid-Download-blue?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.calendar) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.vayunmathur.com/obtainium-link?name=Calendar&packageName=com.vayunmathur.calendar&apk=calendar-release.apk) |
| **Camera** | [![F-Droid](https://img.shields.io/badge/F--Droid-Download-blue?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.camera) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.vayunmathur.com/obtainium-link?name=Camera&packageName=com.vayunmathur.camera&apk=camera-release.apk) |
| **Clock** | [![F-Droid](https://img.shields.io/badge/F--Droid-Download-blue?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.clock) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.vayunmathur.com/obtainium-link?name=Clock&packageName=com.vayunmathur.clock&apk=clock-release.apk) |
| **Contacts** | [![F-Droid](https://img.shields.io/badge/F--Droid-Download-blue?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.contacts) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.vayunmathur.com/obtainium-link?name=Contacts&packageName=com.vayunmathur.contacts&apk=contacts-release.apk) |
| **EverySync** | In Progress | In Progress |
| **Email** | [![F-Droid](https://img.shields.io/badge/F--Droid-Download-blue?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.email) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.vayunmathur.com/obtainium-link?name=Email&packageName=com.vayunmathur.email&apk=email-release.apk) |
| **Find Family** | [![F-Droid](https://img.shields.io/badge/F--Droid-Download-blue?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.findfamily) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.vayunmathur.com/obtainium-link?name=Find%20Family&packageName=com.vayunmathur.findfamily&apk=findfamily-release.apk) |
| **Alchemist** | [![F-Droid](https://img.shields.io/badge/F--Droid-Download-blue?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.games.alchemist) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.vayunmathur.com/obtainium-link?name=Alchemist&packageName=com.vayunmathur.games.alchemist&apk=alchemist-release.apk) |
| **Chess** | [![F-Droid](https://img.shields.io/badge/F--Droid-Download-blue?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.games.chess) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.vayunmathur.com/obtainium-link?name=Chess&packageName=com.vayunmathur.games.chess&apk=chess-release.apk) |
| **Pipes** | [![F-Droid](https://img.shields.io/badge/F--Droid-Download-blue?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.games.pipes) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.vayunmathur.com/obtainium-link?name=Pipes&packageName=com.vayunmathur.games.pipes&apk=pipes-release.apk) |
| **Solitaire** | [![F-Droid](https://img.shields.io/badge/F--Droid-Download-blue?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.games.solitaire) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.vayunmathur.com/obtainium-link?name=Solitaire&packageName=com.vayunmathur.games.solitaire&apk=solitaire-release.apk) |
| **Unblock Jam** | [![F-Droid](https://img.shields.io/badge/F--Droid-Download-blue?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.games.unblockjam) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.vayunmathur.com/obtainium-link?name=Unblock%20Jam&packageName=com.vayunmathur.games.unblockjam&apk=unblockjam-release.apk) |
| **Word Maker** | [![F-Droid](https://img.shields.io/badge/F--Droid-Download-blue?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.games.wordmaker) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.vayunmathur.com/obtainium-link?name=Word%20Maker&packageName=com.vayunmathur.games.wordmaker&apk=wordmaker-release.apk) |
| **Health** | [![F-Droid](https://img.shields.io/badge/F--Droid-Download-blue?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.health) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.vayunmathur.com/obtainium-link?name=Health&packageName=com.vayunmathur.health&apk=health-release.apk) |
| **Maps** | In Progress | In Progress |
| **Messages** | In Progress | In Progress |
| **Music** | [![F-Droid](https://img.shields.io/badge/F--Droid-Download-blue?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.music) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.vayunmathur.com/obtainium-link?name=Music&packageName=com.vayunmathur.music&apk=music-release.apk) |
| **Notes** | [![F-Droid](https://img.shields.io/badge/F--Droid-Download-blue?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.notes) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.vayunmathur.com/obtainium-link?name=Notes&packageName=com.vayunmathur.notes&apk=notes-release.apk) |
| **Office** | [![F-Droid](https://img.shields.io/badge/F--Droid-Download-blue?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.office) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.vayunmathur.com/obtainium-link?name=Office&packageName=com.vayunmathur.office&apk=office-release.apk) |
| **Open Assistant** | [![F-Droid](https://img.shields.io/badge/F--Droid-Download-blue?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.openassistant) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.vayunmathur.com/obtainium-link?name=OpenAssistant&packageName=com.vayunmathur.openassistant&apk=openassistant-release.apk) |
| **Passwords** | [![F-Droid](https://img.shields.io/badge/F--Droid-Download-blue?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.passwords) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.vayunmathur.com/obtainium-link?name=Passwords&packageName=com.vayunmathur.passwords&apk=passwords-release.apk) |
| **PDF Reader** | [![F-Droid](https://img.shields.io/badge/F--Droid-Download-blue?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.pdf) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.vayunmathur.com/obtainium-link?name=PDF&packageName=com.vayunmathur.pdf&apk=pdf-release.apk) |
| **Photos** | [![F-Droid](https://img.shields.io/badge/F--Droid-Download-blue?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.photos) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.vayunmathur.com/obtainium-link?name=Photos&packageName=com.vayunmathur.photos&apk=photos-release.apk) |
| **Travel** | In Progress | In Progress |
| **Weather** | [![F-Droid](https://img.shields.io/badge/F--Droid-Download-blue?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.weather) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.vayunmathur.com/obtainium-link?name=Weather&packageName=com.vayunmathur.weather&apk=weather-release.apk) |
| **YouPipe** | [![F-Droid](https://img.shields.io/badge/F--Droid-Download-blue?logo=f-droid&style=flat-square)](https://f-droid.org/packages/com.vayunmathur.youpipe) | [![Obtainium](https://img.shields.io/badge/Obtainium-Download-purple?style=flat-square)](https://api.vayunmathur.com/obtainium-link?name=YouPipe&packageName=com.vayunmathur.youpipe&apk=youpipe-release.apk) |

# Contributions

## Translations
Contribute to translations for these apps through the weblate project: https://hosted.weblate.org/projects/modern-apps/

# App Feature Descriptions
Just for the "interesting" apps

## Email

Supports any email provider and supports push notifications for emails (so you get emails immediately instead of waiting for the app to check in with the server again).

## FindFamily

iOS: https://apps.apple.com/us/app/find-family-secure-private/id6760863634

### Features:
- End-to-end encryption
- No emails, phone numbers, or any other personal information required
- Saved places, with notifications when people enter or leave them
- See battery status / low battery notifications
- Show how long person has been at place (saved or not)
- See where you were at any point in the past
- Temporary location sharing via link (opens in website)

### In progress:
- UWB Precision Finding (direction and distance)
- Airtag-like hardware trackers

## Maps
Officially unreleased, but the apk is available for download for testing.
### Features
- Offline Maps (download regions)
- Display OSM data for POIs
- Check Google Reviews Ratings for POIs
- Search for nearby POIs
- Offline routing for walking and biking
- On-device driving routing (including traffic)
- Offline routing for public transit (no live schedules though)

### In progress:
- Use live schedules for public transit

## Office
First ever FOSS full office suite for Android that uses modern Android UI.

Supports viewing and editing documents in the odf family (odt, ods, odp, odg)

If you are curious about how word processors / office software works, I would *highly* encourage reading the code here. It's actually only like 6 files, so it should be quite accessible even for beginners. I try to make all my code easily accessible for beginners (one of my goals here is to build easy-to-understand reference implementations of these apps too), but I had to point this one out for those who might feel intimidated by Android's first native Android word processor.

## Messages
The private aggregator for messaging apps. There exist other aggregators, but those apps run bridges on their own servers (privacy risk because your messages are or can be decrypted by the server), while this app runs the bridges on-device, preserving end-to-end encryption.

Currently supports Google Messages, Whatsapp, and Google Voice with more services in development.

## OpenAssistant
A 100% offline AI app that supports text, image, and audio input using the Gemma 4 models

Also supports tool calling, which enables interactions with other apps in this ecosystem (reading and writing to notes, searching and playing music, etc.)

## YouPipe
A YouTube frontend using Material 3 You - based on the NewPipe Extractor library. Supports downloading videos, and and importing data directly from NewPipe or Youtube (via Google Takeout).

Also includes a simple (will be improved as time goes on) algorithm to suggest videos you might like. The algorithm runs 100% on-device, and is based solely on watch history, subscriptions, and (eventually) explicit user input.
