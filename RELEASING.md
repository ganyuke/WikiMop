# Releasing WikiMop

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`. `versionName` is three numbers, like `1.0.0`.
2. Write the changelog in `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.
3. Commit, tag it `v<versionName>`, and push the tag.

GitHub Actions builds and signs the APK and puts it on the releases page. This needs the `RELEASE_KEYSTORE_BASE64` and `RELEASE_KEYSTORE_PASSWORD` secrets. [nextcolor-apps](https://github.com/ganyuke/nextcolor-apps) picks it up for F-Droid within a day.

The release key is `nextcolor-release.p12`, the same one for every NextColor app. The first secret is the output of `base64 -w0 nextcolor-release.p12` and the second is its password.

To sign a build on your own machine, make a `keystore.properties` next to `settings.gradle.kts`:

```properties
storeFile=/path/to/nextcolor-release.p12
storePassword=your-password
```
