#!/bin/sh
if [ "${ACCEPT_LICENSE}" != "Y" ] && [ "${ACCEPT_LICENSE}" != "YES"  ]
then
  echo "CORDA ENTERPRISE – SOFTWARE EVALUATION LICENSE AGREEMENT must be accepted before CORDA ENTERPRISE can start.
CORDA ENTERPRISE may be used for evaluation purposes for 90 days pursuant to the Software Evaluation License Agreement.
Any use beyond this (e.g. in production deployments) requires a commercial license. Please contact sales@r3.com for more information.

The Software Evaluation License Agreement for this product can be viewed from https://www.r3.com/corda-enterprise-evaluation-license.
You can accept the Software Evaluation License Agreement by setting the ACCEPT_LICENSE environment variable to \"Y\", \"ACCEPT_LICENSE=Y\"" >&2
  exit 1
else
  echo "CORDA ENTERPRISE – SOFTWARE EVALUATION LICENSE AGREEMENT has been accepted, CORDA ENTERPRISE will now continue.
The Software Evaluation License Agreement for this product can be viewed from https://www.r3.com/corda-enterprise-evaluation-license.
A copy of the Software Evaluation License Agreement also exists within the /license directory in the container."
fi

exec "$@"