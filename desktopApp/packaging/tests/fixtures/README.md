`appimage-update-7eaa.sh` is frozen verbatim from commit
`7eaa99bc89111d6ad2c33c44f617ed76ae57e87e`, for the installed-helper compatibility test.
It intentionally retains the old helper's unsafe timeout/recovery behavior. It is not
included in production resources or downloaded by the updater. Do not use it to perform
real updates. The test proves a newly packaged JVM can acknowledge the legacy protocol
without weakening the token checks used by the current bundled helper.
