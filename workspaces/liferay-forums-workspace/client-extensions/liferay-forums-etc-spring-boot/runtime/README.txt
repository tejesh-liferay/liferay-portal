Liferay Forums Microservice
===========================

Requires Java 17 or later. Listens on port 58081. Liferay calls it at
http://localhost:58081, so port 58081 on the Liferay machine must reach it.

1. Deploy this zip to Liferay by copying it into
   <liferay-home>/osgi/client-extensions.

2. Unzip it into a directory of your choice, on the Liferay machine or on
   another machine.

3. Start it.

   On the Liferay machine, point LIFERAY_HOME at the Liferay installation. It
   reads the Liferay host and its OAuth credentials from
   <liferay-home>/routes/default:

   Linux:    LIFERAY_HOME=/opt/liferay ./start.sh
   Windows:  set LIFERAY_HOME=C:\liferay
             start.bat

   On another machine, fill in application.properties, then run ./start.sh or
   start.bat without LIFERAY_HOME. Also forward port 58081 on the Liferay
   machine to port 58081 on this machine, for example by running this on the
   Liferay machine:

   socat TCP-LISTEN:58081,bind=127.0.0.1,fork,reuseaddr TCP:<this-machine>:58081

4. Check that it is up:

   curl http://localhost:58081/ready

   On another machine, also run it on the Liferay machine to check the
   forward.