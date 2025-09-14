# COMP SCI 3012 - Distributed System 
Assignment 2 - Weather Aggregation System   
Student name: Gia Bao Au    
Student ID: a1897967

## Assignment Overview 
The main goal of this assignment was to create an aggregation server that capable of collecting weather
updates from different content servers and serve consistent weather data response to multiple clients.

## 📦 Tool and Dependencies 
1. Java SDK 24
2. IntelliJ IDE Community Edition
3. Gson 2.11.0 (add via Maven or manually):
    - Go to Project Structure -> Module -> Click on Add or + if not there
    - Search and add this: firstbird.backbone.gson_2.11
    - Search and add this: com.google.code.gson:gson:2.11.0
    - gson-2.11.0.jar (Download link: https://repo1.maven.org/maven2/com/google/code/gson/gson/2.11.0/)
4. Operating System: Windows 10

# 📝 Note
1. GET clients always retrieve the newest weather data from each content server, ordered Lamport Clock.
2. Both Content Server and Client update their onw clocks based on the lamport timestamp returned by Aggregation Server.
3. If you have done some **manual** testing and would like a restart, please:
   - restore the feed.json to [] 
   - Clear the updates.wal
4. source_id was used as key when updating Write-Ahead-Log (WAL) as we want the latest record from each content server 
5. req payload id was used to check if a record need to be updated
6. If you failed to start the Aggregation Server after Junit testing (it is likely that the port isn’t released properly), do the following:
```bash
   netstat -ano | findstr :4567
```
```bash
taskkill /PID <PID> /F
```

# 📌 Feature List:
**Aggregation Server**:
1. Receive and update weather record from content servers.
2. Orders requests by Lamport Clock (If equal then use arrival sequence following strict FIFO)
3. Remove stale content from Out of Contact Server after 30s
4. Persist data with WAL and periodic snapshots
5. Handle crash by replaying WAL and restore server state to before the crash

**HTTP server code**
1. 200 (Content Server + Client) - Received content server and client requests
2. 201 (content server) - First time connection created
3. 204 - Reject PUT with no content
4. 400 - Reject method not PUT or GET
5. 500 - Internal Server Error

**Content-Server**:
1. Sends weather records as PUT requests to the Aggregation Server.
2. Maintains its own Lamport Clock, updated via ACKs from Aggregation Server.
3. Support Retry Mechanism when a content server tries to send data, but the aggregation server is down 

**Client**:
1. Requests the latest weather data from Aggregation Server.
2. Updates its Lamport Clock based on server response.
3. Displays consistent weather data with causal order.

# 🛠️ Crash Recovery
When starting up:
1. The Aggregation Server load the last snapshot (feed.json)
2. Replay the Write-Ahead-Log (WAL) 
3. Updating any entries came after the snapshot

When getting a PUT request: 
1. Check if the record already in memory 
2. Check if it needs to overwrite the current record
3. If new record, append to WAL
4. Write to persistent data storage (memory_store)
5. Write snapshot periodically

# 🚀 Setup & Compilation
Compile all files:  
On Windows 💻:
```bash
javac -cp "out;gson-2.11.0.jar" -d out src/main/java/org/example/*.java
```
On Linux/MacOS 🐧:
```bash
javac -cp "out:gson-2.11.0.jar" -d out src/main/java/org/example/*.java
```

### 🖧 Run Aggregation Server
On Windows 💻:
```bash
java -cp "out;gson-2.11.0.jar" org.example.AggregationServer 
```

On Linux/MacOS 🐧:
```bash
java -cp "out:gson-2.11.0.jar" org.example.AggregationServer 
```

### 🗄️ Run Content Server
By default, it will Start with default weather record (sample.json),    
If you want to start content server with a different weather record, please change the 
cs-data/sample.json in the below command to <your_new_file_location> or use the interactive menu.   
(I have made several files for testing, all of them can be found in the folder **cs-data/**)

On Windows 💻:
```bash 
java -cp "out;gson-2.11.0.jar" org.example.ContentServer http://localhost:4567/weather.json cs-data/sample.txt 1
```
On Linux/MacOS 🐧:
```bash 
java -cp "out:gson-2.11.0.jar" org.example.ContentServer http://localhost:4567/weather.json cs-data/sample.txt 1
```
(You can also choose option 2 in the menu after running this command to change the weather record you want to send)

Instruction:
1. Start up content server with the above java command
2. By default, your content server will read from cs-data/sample.json
3. To send a different weather json, please choose 2 and specify the location of that file (eg: cs-data/sample2.txt)
4. Choose 3 if you want to exit the program

### 🖥 Run GET Client
On windows 💻:
```bash 
java -cp "out;gson-2.11.0.jar" org.example.GETClient  http://localhost:4567/weather.json
```
On Linux/MacOS 🐧:
```bash 
java -cp "out:gson-2.11.0.jar" org.example.GETClient  http://localhost:4567/weather.json
```

# 🐞 Automated Testing 
Compile the test file with:     
On windows 💻:
```bash
javac -d out -cp "gson-2.11.0.jar;junit-platform-console-standalone-1.9.3.jar;out" src/test/AggregationServerTest.java
```
On Linux/MacOS 🐧:
```bash
javac -d out -cp "gson-2.11.0.jar:junit-platform-console-standalone-1.9.3.jar:out" src/test/AggregationServerTest.java
```

Now run the test class (run all test):  
On windows 💻:
```bash
java -jar junit-platform-console-standalone-1.9.3.jar -cp "out;gson-2.11.0.jar" --scan-class-path
```
On Linux/MacOS 🐧:
```bash
java -jar junit-platform-console-standalone-1.9.3.jar -cp "out:gson-2.11.0.jar" --scan-class-path
```

# 🔀 How Do We Handle Concurrency?
When 2 (or more) requests come at the same time:
- The order of arrival is non-deterministic
- It depends on the OS to decide which hit the Agg Sv first but this is not reliable
- Even if we have a queue to store [ PUT1, PUT2 ], PUT2 might get overwritten by PUT1 if we don't check the Lamport clock 

Since a PriorityBlockingQueue is used:
- If a PUT arrives with Lamport=5, then a GET arrives with Lamport=4, the GET will be processed first (since Lamport 4 < 5).
- If Lamports are equal, the arrival_seq guarantees strict FIFO ordering within that Lamport tick.

# 🔒 Race Condition and Deadlock
Reentrant clock is used for protecting critical sections:
1. apply_put()
2. last_update
3. start_expiry_checker()
4. handle GET request

