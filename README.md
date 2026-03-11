# Modern Apps
This is a collection of apps that aims to replace the ecosystem feel of proprietary ecosystems like Apple and Google, but with 100% open-source apps.

Unlike other open-source ecosystems, all apps in Modern Apps are built using modern technologies: Jetpack Compose, Material 3 You, etc, which makes them "look nice" and easy to read for those who wish to audit the code.

This ecosystem also contains apps that aren't as well covered by the open-source ecosystem - particularly Find Family (a location sharing app), YouPipe (a privacy-based youtube frontend), and the upcoming Maps (which will support public transit routing, a feature missing from existing alternatives).

This ecosystem also includes games, which I personally believe are an important part of a good phone experience (at least the kind from the "golden age" of mobile games are), so I have remade some mobile games I enjoyed and might add more as time goes on.

Note: the v2.x.x versions of these apps are all to be considered beta versions. Lots of features/apps being done in a short period of time will inevitably result in some bugs here and there. All bug reports are appreciated, and any PRs to add fixes or new features (as long as not bloaty) are very appreciated. Version numbering will change to v3.x.x when all the apps are stable.

# App Feature Descriptions
Just for the "interesting" apps

## FindFamily
### Features:
- End-to-end encryption
- No emails, phone numbers, or any other personal information required
- Temporarily turn on or off location sharing with specific people
- Saved places, with notifications when people enter or leave them
- See battery status / low battery notifications
- Fully open source client
- Share current speed
- Show how long person has been at place (saved or not)
- Show location history
- Temporary location sharing via link (opens in website)

### In progress:
- Track any bluetooth device

### Security Summary
When the app is first started, it generates a random userID, as well as an encryption and decryption key.

The userID and the encryption key are sent to the server, which stores them in a database. The decryption key is kept on your device.

When you share your location, the app gets your friend's encryption key from the server, encrypts your location with it, and sends it to the server.

An example message to the server looks like the following:
```
{
    "recipientUserID": "748974347624",
    "encryptedLocation": "GWwkSIKuWFFEVrH1GOv7XWFDbHt/cDUC6FPsQF6
        +9YUL2uoDSwGn255rsz1unIhKIOstOjjpUmVD72XCEBtfxfvjU2K+R14
        +yKJwlyY4zEoVNIcgLT6zzSXPirN2z6DqYFD4iv73McylwNUtnYeOBBM
        FolFODR7vtZYhfpVKJZrX7DLRkfTcW1IqWmduKhla9u4sglK2JAeg2YS
        9VNIK1ou0EbssyJXwxL38K2IEsuXcEp3qBoUlJyolKEzozNvUnFsxvrV
        oXctvO+ynTZAVYsgykLPiOSU="
}
```
The only information the server can read is an anonymous ID representing who the shared location is for. The server cannot read the location itself, as the decryption key is only on the recipient's device.

If you are using Android, the connection is made through Tor, so the server won't know your IP address either.

## Maps
Officially unreleased, but the apk is available for download for testing.
### Features
- Offline Maps (download regions)
- Display OSM data for POIs
- Check Google Reviews Ratings for POIs
- Search for nearby POIs
- Offline routing for walking and biking
- Online routing for driving (including traffic) and public transit (including timings)

### In progress:
- Move away from Google APIs for routing driving and public transit (both will still require internet for traffic and GTFS respectively)

## YouPipe
A youtube frontend using Material 3 You - based on the NewPipe Extractor library

#### In progress:
- Downloading videos
- Playing audio-only mode
- Better import/export (newpipe import currently exists)
