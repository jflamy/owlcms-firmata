#!/bin/bash -

# windows version number mapping

# TAG = x.y.z  x = major, y = minor
# z = 0 : normal release
# z > 0 : bug fix release
export TAG=2.6.0-rc01

# use -alpha00 or -beta00 or -rc00 or empty for final release
export ALPHA_BETA_RELEASE=-rc01

echo building $TAG
(cd ../firmata4j; mvn -DskipTests install)

mvn versions:set -DnewVersion=$TAG

# Copy device configuration files into jar resources
rm -rf src/main/resources/devices
mkdir -p src/main/resources/devices
find diagrams -name '*.xlsx' -print0 | xargs -0 -I {} cp {} src/main/resources/devices/

mvn -Pproduction clean package

mkdir -p dist/files
cp target/owlcms-firmata.jar dist/files

git add pom.xml
git add --all
git commit -m $TAG
git push
gh release delete $TAG -y
gh release create $TAG --notes-file RELEASE.md -t "owlcms-firmata $TAG"
gh release upload $TAG dist/files/owlcms-firmata.jar

# get the newly created tag back
git pull