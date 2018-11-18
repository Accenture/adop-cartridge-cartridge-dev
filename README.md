# Cartridge Specification
The purpose of this repository is to provide utilities that save time when developing [DevOps Platform](https://github.com/Accenture/adop-docker-compose) cartridges based on this [cartridge specification](https://github.com/Accenture/adop-cartridge-specification).

## Prerequisites

* Make sure [adop-pluggable-scm](https://github.com/Accenture/adop-pluggable-scm) is loaded into your ADOP/C.

## Stucture
The structure is expected to conform to CARTRIDGE_SDK_VERSION 1.0

## Using this Repository
Load this as a normal cartridge.  Load this via a running instance of this cartridge and use the tools to enhance it.

### Jobs ###


#### CreateNewCartridge ####
Specify a cartridge name and it will provide new repository local to your Gerrit instance.

#### ValidateCartridgeRepo ####
Ensure your cartridge still fits the specification.

#### LoadTestCartridge ####
Load in cartridge jobs from your local Gerrit instance.

#### PublishCartridgeRepo ####
Publish your cartridge to a remote repository from Gerrit.
