#!/bin/sh
mvn install:install-file -Dfile=$1 -DgroupId=com.craftandresolve -DartifactId=relax -Dversion=$2 -Dpackaging=jar -DcreateChecksum=true -DupdateReleaseInfo=true -DlocalRepositoryPath=.
