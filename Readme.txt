cd D:\ecom\Ecom\ecom-backend
powershell -NoProfile -ExecutionPolicy Bypass -File .\run-all-services.ps1 start
It automatically loads your .env and starts all core backend services in dependency order.
To include the frontend:
.\run-all-services.ps1 start -StartFrontend
To also include legacy alerts and Kafka:
.\run-all-services.ps1 start -StartFrontend -EnableLegacyAlerts
You need Java 21, Maven, MySQL with the required schemas, and your .env configuration. The frontend also requires Node.js/npm. Missing passwords or synchronization keys will prompt you for input.
Once started:
- Frontend: http://127.0.0.1:55173
- API gateway: http://localhost:58082
- Eureka: http://localhost:58761
- Logs: D:\ecom\Ecom\.local\logs
Restart or stop backend services:
.\run-all-services.ps1 restart
.\run-all-services.ps1 stop
If you started the frontend, stop it separately:
.\run-all-services.ps1 stop frontend

Test-NetConnection localhost -Port 58082
Invoke-RestMethod http://localhost:58082/actuator/health

macOS and Linux
---------------
Use the Bash launchers from the backend directory. Java 21, Maven,
MySQL, netcat (nc), and curl are required; npm and Node.js are needed only
when starting the frontend. The MySQL server must already be running. The
launcher uses Java 21; it prefers a Java 21 JDK from JAVA_HOME, macOS
java_home, or common local JDK locations. On macOS, select an installed
Java 21 with: export JAVA_HOME=$(/usr/libexec/java_home -v 21)

chmod +x ./run-all-services.sh ./stop-all-services.sh
./run-all-services.sh start
./run-all-services.sh start --frontend
./run-all-services.sh start --legacy-alerts
./run-all-services.sh restart
./run-all-services.sh stop
./run-all-services.sh stop frontend

The launcher loads KEY=value settings from .env beside the script (or env if
.env is absent) without executing the file. Set ENV_FILE to use another path.
Existing environment variables take precedence. Logs and
PID files are stored under ../.local/logs and ../.local/pids. Legacy alerts
require Kafka; the launcher can start it from KAFKA_HOME (or ../tools/kafka
or ./tools/kafka) when its Unix launcher and KRaft config are present.
Unlike the Windows launcher, it does not start a system MySQL service and
will not kill untracked processes to clear a port.

For a single-host Linux deployment, services bind to and register with Eureka
as 127.0.0.1 by default, so the local gateway can reach them without exposing
their application ports. If services run on separate hosts, set SERVER_ADDRESS,
EUREKA_INSTANCE_HOSTNAME, and EUREKA_INSTANCE_IP_ADDRESS in .env to addresses
reachable between those hosts before starting the launcher.


Role	Example username
Super admin	admin
Global admin	globaladmin
Location admin	locationadmin
Store manager	storemanager
Delivery partner	deliverypartner
Customer	demo.customer@example.com
Customer	priya.nair@example.com

password : admin123

sudo chown -R "$(id -un):$(id -gn)" http-sync/target
sh ./run-all-services.sh
