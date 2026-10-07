# Security policy

## Supported versions

Only the latest published Winlator Secure release is intended to receive
security fixes.

## Reporting

Use GitHub's private security-advisory reporting for the public repository.
Do not open a public issue containing an unpatched vulnerability, credentials,
private signing material, or personal diagnostic data. If private reporting is
not enabled, open a minimal issue asking the maintainer for a private contact
channel without including vulnerability details.

Reports should include the affected version, reproduction steps, impact, and
suggested remediation. Do not upload user data or third-party secrets.

## Trust and signing

Source availability does not authenticate an APK. Verify release checksums,
the detached signature, and the documented Android signing-certificate
SHA-256 fingerprint before installation. Keys and credentials are maintained
outside this repository. Runtime downloads listed in
`download-integrity.json` are accepted only after size and SHA-256 validation.
