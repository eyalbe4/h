#!/bin/bash

set -x

mvn -e -B clean package -s ~/Projects/maven-plugins/settings.xml
rm -rf ~/.hudson/plugins/artifactory/ ~/.hudson/plugins/artifactory.hpi
cp target/artifactory.hpi ~/.hudson/plugins/
