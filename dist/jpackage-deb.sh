#!/bin/bash -

# x.y.z  x = major, y = minor
# z = 0 : normal release
# z > 0 : bug fix release
# z00 .. z19 = alpha
# z20 .. z39 = beta
# z40 .. z49 = rc
# z90 .. z99 = release

export VERSION=1.2.090
export TAG=1.2.0

echo building $TAG "(" $VERSION ")"

#(cd ../../firmata4j; mvn -DskipTests install)
(cd ..; mvn versions:set -DnewVersion=$TAG;)
(cd ..; mvn -Pproduction clean package)

cp ../target/owlcms-firmata.jar files
cp -r ../src/main/resources/devices files
rm *.exe 2>/dev/null
rm -rf files/devices/wokwi
mkdir files/devices/wokwi
find ../diagrams -name '*.xlsx' -print0 | xargs -0 -I {} cp {} files/devices/wokwi

echo jpackage...

jpackage --type deb --input files --main-jar owlcms-firmata.jar --main-class app.owlcms.firmata.ui.Main \
 --name owlcms-firmata --icon files/owlcms.png --runtime-image jre \
 --app-version ${VERSION} 
