# Security policy

This is an experimental personal project. Security reports are accepted for the current main branch; there is no security-support SLA or supported production release.

## Report privately

Use GitHub's **Report a vulnerability** form:
https://github.com/aalomair/personal-finance-android/security/advisories/new

Do not open a public issue for a vulnerability. Include a minimal reproduction using invented data, affected version/commit, impact and suggested mitigation. Never include provider keys, real SMS, account/phone numbers, device database files or personal screenshots. If private reporting is temporarily unavailable, do not post sensitive details publicly; wait until the private channel is available.

If a key may have leaked, revoke/rotate it at the provider immediately. Removing it from a commit or from the app is not revocation. A suspected data exposure should be handled privately before public discussion.

The app sends candidate SMS to the provider you select; see README for the data flow, local storage, retention and limitations. Automated tests and repository scanning are useful checks, not a guarantee of security or an independent security audit.
