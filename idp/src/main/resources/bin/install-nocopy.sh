#! /bin/bash

declare LOCATION

LOCATION=$0
LOCATION=${LOCATION%/*}

$LOCATION/ant.sh "$@" install-nocopy
