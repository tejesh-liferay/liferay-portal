Liferay Forums Microservice
===========================

Requires Java 17 or later.

1. Deploy this zip to Liferay by copying it into
   <liferay-home>/osgi/client-extensions.

2. Unzip it into a directory of your choice on the machine that will run the
   microservice.

3. Edit application.properties in that directory: set the Liferay host,
   protocol, and the OAuth client ID and secret. The microservice does
   not start while the client ID or secret is empty.

4. Start it:

   Linux/macOS:  bash start.sh
   Windows:      start.bat

5. Check that it is up:

   curl http://localhost:58081/ready
