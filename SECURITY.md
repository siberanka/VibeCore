# Security policy

VibeCore is under active development and has no stable release yet. Development builds receive fixes as part of current work; no production support guarantee is offered.

## Reporting a vulnerability

Report suspected vulnerabilities privately to **siberanka** by opening a [confidential issue on GitLab](https://gitlab.com/siberanka/vibecore/-/issues/new?issue[confidential]=true). Do not post exploit details, credentials, player records or server backups in public issues.

Include the affected version, exact Paper build and Java version, the relevant configuration with secrets removed, a minimal reproduction using dummy data and the observed security impact. Local evidence is sufficient; do not test against other operators' servers.

## Operator guidance

Administrative commands require the `vibecore.admin.*` permissions, which default to operators. Grant them only to trusted staff. The planned editor, pack hosting and remote media features must complete their authentication, authorization, file access and resource limit reviews before they are enabled.
