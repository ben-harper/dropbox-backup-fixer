# Dropbox Backup Fixer

An Android application designed to help you verify that all of your device's photos and videos have been successfully backed up to Dropbox. 

## Features

**Phase 1 (Completed):**
* Scans local device for all photos and videos
* Generates a detailed breakdown of files by folder, year, and file type
* Exports a comprehensive `.xlsx` Excel report of the entire media library

**Phase 2 (Upcoming):**
* Dropbox OAuth Integration
* Cross-referencing local files against the Dropbox cloud
* Automated discrepancy resolution to upload missing files

## Build Instructions

To build the project locally using Gradle:
```bash
./gradlew assembleDebug
```
