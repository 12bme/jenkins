#!/bin/bash

MASTER_SNAPSHOT=${MASTER_HOME}.zip
if [ -d "${MASTER_HOME}" ]
then
  rm -f ${MASTER_SNAPSHOT}

  cd ${MASTER_HOME}
  zip -q -r ${MASTER_SNAPSHOT} * 1>&2

  rm -fr ${MASTER_HOME}
fi
echo "MASTER_SNAPSHOT=file:${MASTER_SNAPSHOT}"
exit 0
