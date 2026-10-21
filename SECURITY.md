# Security Policy

## Supported Versions

To receive fixes for security vulnerabilities it is required to always upgrade to the latest version of OpenRemote.

Fixes will only be released for previous releases under special circumstances.

## Reporting a Vulnerability

You can report a security vulnerability either through email, or as a GitHub security advisory. If you are uncertain what you have
discovered is a vulnerability or you believe it is a critical issue please report using email (or both).

### Report One Vulnerability At A Time

Please report each vulnerability separately, even when you found them together and even when they share a theme. If several of them chain into a larger attack, describe the chain in a comment on each report and link them to one another.

GitHub will not issue a CVE for an advisory that covers more than one independently fixable vulnerability. This follows the CVE CNA rules, specifically [4.1 vulnerability determination](https://www.cve.org/ResourcesSupport/AllResources/CNARules#section_4-1_Vulnerability_Determination) and [4.2 CVE ID assignment](https://www.cve.org/ResourcesSupport/AllResources/CNARules#section_4-2_CVE_ID_Assignment) (4.2.6 and 4.2.11). A report that bundles several has to be split before any of it can be published, which delays the fix and your credit for all of them.

The rule of thumb is whether one change would fix everything you are describing. If two findings need two separate fixes, they are two reports.

### Behaviour That Is Intended

Some features exist to let OpenRemote reach other systems or to allow limited anonymous access for public apps/dashboards. Reports that only show these features doing what they are for are closed without an advisory.

#### Outbound connections to user-defined addresses

Integrations connect to whatever address they are configured with, internal addresses included. This covers webhook actions in rules, the HTTP agent and every other agent protocol that opens a connection (MQTT, WebSocket, TCP, UDP, SNMP, Modbus TCP, KNX, mail and others). Reaching devices and services on private networks is the reason these features exist, so we do not restrict destinations in the application.

Creating these needs `write:rules` or `write:assets`, and we treat holding those roles as permission to define integrations for the realm. Restricting where the manager can connect is the job of the deployment: run it in its own network segment and apply egress rules, for example blocking cloud metadata addresses such as `169.254.169.254`.

#### Notification acknowledgement and delivery

The HTTP API endpoints for notification delivery and acknowledgement do not require authentication but do require the notification ID and target ID, this is by design for anonymous consoles.

The following are still in scope:

- Reaching one of these features without the role it requires, or from another realm.
- A response, credential or secret becoming readable by someone who could not read it otherwise.
- Bypassing a destination restriction that an operator has configured.

### What To Include

- The version, release tag or commit hash you tested against. A floating tag such as `develop` does not identify a build, so we cannot tell what you were running.
- Steps to reproduce, and what you observed that shows the issue is real rather than theoretical.
- The affected endpoint, class or file.
- The full CVSS vector rather than only a number, so the score can be checked. We prefer 4.0 over 3.1.

### Where To Send It

To report through email send an email to security@openremote.io

To report through GitHub go to [https://github.com/openremote/core/security/advisories/new](https://github.com/openremote/core/security/advisories/new)

If you have a patch for the issue please use `git format-patch` and attach to the email or issue. Please do not open a
pull request on GitHub as that may disclose sensitive details around the vulnerability.

### What Happens Next

Your report is reviewed and you are told the outcome, whatever it is.

We reproduce the issue before writing a fix. If we cannot, we will come back to you rather than close it silently.

We agree the severity within the team and re-evaluate the vector you supplied. If we arrive at a different score from the one you assigned, we will provide a clear rationale for the change.

If your report turns out to describe the same vulnerability as another one, the reports are merged and everyone who found it is credited on the advisory that is published.

If a fix is planned, the advisory is published once a release containing it is publicly available. If we do not intend to fix the vulnerability, the advisory is published anyway, with the mitigations documented.

The process behind all of this is documented in section 6 of the OpenRemote handbook, [Vulnerability Management and Coordinated Disclosure](https://handbook.openremote.io/docs/vulnerability-management-and-coordinated-disclosure).
