# Java PAA Template Instructions

This template is a public starter implementation of the Personal AI
Architecture (PAA). Keep the vendored contracts, `specs/` mirror, and
`paa.lock.json` synchronized. Run `mvn test` and `sh scripts/validate.sh` before
submitting changes.

Provider-specific protocol details belong under
`src/main/java/com/paa/adapters/`; Gateway, Agent Loop, memory, and public
contracts must remain provider-neutral.
