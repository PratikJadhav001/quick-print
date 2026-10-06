CAMPUS REUSE / QUICKPRINT — Run instructions

This project keeps the supplied QuickPrint frontend unchanged and adds a Java 11+
backend and MySQL persistence layer. Student orders receive a token; the shop
dashboard reads the same database and can update token status.
Selected PDFs are saved in the local uploads/ folder (up to 15 MB each) and
their generated file IDs are recorded with the order.
The shop dashboard shows each uploaded filename as a clickable link so the
shopkeeper can open the original PDF in a new browser tab.

Project layout
  database/schema.sql       MySQL database and table
  frontend/                 supplied visual frontend and assets
  lib/mysql-connector-j.jar MySQL JDBC driver (add it yourself)
  src/Db.java               database connection helper
  src/Main.java             HTTP server, APIs, and static-file server
  config.properties         database credentials and server port
  uploads/                  created automatically when a PDF is uploaded

1. Install JDK 11 or newer and MySQL 8.
2. In MySQL Workbench, open and run database/schema.sql.
3. Download MySQL Connector/J 8.x and save its JAR as:
       lib/mysql-connector-j.jar
4. Update db.user and db.password in config.properties.
5. Windows: double-click run.bat. Mac/Linux: chmod +x run.sh && ./run.sh
6. Visit http://localhost:8080

API used by the frontend
  POST  /api/shop-orders
  GET   /api/shop-orders?status=ALL|PENDING|PRINTING|READY|COLLECTED
  PATCH /api/shop-orders/{token}/status
  GET   /api/shop-prices
  PUT   /api/shop-prices
  GET   /api/health

The frontend uses a same-origin API, so no CORS setup is needed. Do not commit
real credentials if you move this project into source control.
