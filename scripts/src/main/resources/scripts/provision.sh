#!/bin/bash

WORK="$(dirname "$0")"

if [ ! -d "${MASTER_HOME}" ]
then
  mkdir -p "${MASTER_HOME}/plugins"
  cp "${WORK}/cloudbees-metanectar-plugin.hpi" "${MASTER_HOME}/plugins/cloudbees-metanectar-plugin.hpi"

 echo "java -DMASTER_ENDPOINT=http://localhost:${MASTER_PORT} -DJENKINS_HOME=${MASTER_HOME} -DMASTER_METANECTAR_ENDPOINT=${MASTER_METANECTAR_ENDPOINT} -DMASTER_GRANT_ID=${MASTER_GRANT_ID} -jar "${WORK}/jenkins-war.war" --httpPort=${MASTER_PORT} &> ${MASTER_HOME}.log.txt &" > ${MASTER_HOME}.start
 echo -n 'echo $! > ${MASTER_HOME}.pid' >> ${MASTER_HOME}.start
fi

echo -n "MASTER_ENDPOINT=http://localhost:${MASTER_PORT}"
