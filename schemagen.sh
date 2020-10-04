#!/bin/bash

mvn \
    exec:java \
    -Dexec.mainClass=io.xlate.edi.schematools.SchemaGenerator \
    -Ddialect=${1} \
    "${@:2}"
