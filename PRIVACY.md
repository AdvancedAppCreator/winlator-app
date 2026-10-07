# Privacy

Winlator Secure does not operate an analytics service and does not intentionally
upload game files, Wine logs, screenshots, OCR input, or translated text.

## ML Kit

Game Text features use Google ML Kit for on-device text recognition, language
identification, and translation. Image and text inputs and their results are
processed on the device. According to the
[ML Kit terms](https://developers.google.com/ml-kit/terms), ML Kit may contact
Google to obtain fixes, updated models, and hardware-compatibility information,
and sends performance and utilization metrics to Google. Google's processing
of those metrics is governed by the
[Google Privacy Policy](https://policies.google.com/privacy).

Translation models may be downloaded and stored by ML Kit on the device. They
can be removed through Winlator's Game Text model controls or by clearing the
application's data.

## Upstream runtime download

Before installing its system files, Winlator Secure asks the user to download
the pinned official Winlator 11.1 APK directly from GitHub over HTTPS. The
complete APK is verified, three required runtime archives are imported into
private application storage, and the temporary APK is deleted. GitHub receives
the network request and related connection metadata under GitHub's privacy
terms. Cancelling or failing verification removes the partial private files.

## Prerequisite downloads

When the user approves Windows prerequisite setup, Winlator downloads the
selected installers directly from Microsoft-controlled HTTPS addresses.
Winlator verifies each file against the expected SHA-256 value before running
it inside the local Wine container. The verified installers may remain cached
in Winlator's private application storage for repair or reinstallation.

## Local data

Containers, settings, installation records, translation caches, and diagnostic
logs are stored locally and are excluded from Android backup. Android,
Microsoft installers, Google ML Kit, and
approved companion applications remain subject to their own privacy terms and
permissions.
