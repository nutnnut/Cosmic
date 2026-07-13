@echo off
@title Cosmic
rem Heap sized for the AI-bot population (2000-bot target). Old gen ran ~94%% full at 473 bots on
rem 2-4GB, causing young-GC evacuation-failure stop-the-world pauses that stalled every bot tick
rem (multi-second combat-buffs/common-systems spikes). 8GB + G1 with an earlier concurrent-mark
rem trigger (IHOP=40) keeps old gen from filling, so those STW stalls don't happen.
java -Xms8g -Xmx8g -XX:+UseG1GC -XX:MaxGCPauseMillis=150 -XX:InitiatingHeapOccupancyPercent=40 -Dwz-path=wz -jar target\Cosmic.jar
pause
