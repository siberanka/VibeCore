# Contributing

Open an issue on [GitHub](https://github.com/siberanka/VibeCore/issues) or [GitLab](https://gitlab.com/siberanka/vibecore/-/issues) describing the player or operator need before beginning a substantial change. Include observable acceptance criteria and the affected module. VibeCore is under active development, so check the roadmap first.

Build and test with JDK 25 using `mvn verify`. Changes need appropriate checks and exact Paper/client version records for runtime behavior. Keep world and player operations on the server thread. Perform bounded file and media processing away from that thread, returning results through the scheduler.

Use the `com.siberanka.vibecore` package tree. Keep dependencies explicit and optional integrations isolated. New modules must expose an honest availability state and clean up their tasks, sessions and entities when disabled.

Do not include credentials, player data, server backups, local working material or generated build output in a contribution. Only submit code and assets you have permission to distribute. Record applicable dependency licenses and preserve required copyright notices.

Use an imperative commit subject, for example `Add validated menu configuration`. Describe the concrete problem, resulting behavior and validation in the merge request. Do not add hosted workflow or pipeline configuration without maintainer approval.

Before publication, the maintainer runs `scripts/check-publication.ps1` with a local deny list against the staged files, and with `-Tracked` against the full tracked tree. Passing this check supplements manual review; it does not authorize publication.

By submitting a contribution you agree that it is distributed under the [VibeCore License](LICENSE) together with the rest of the project.
