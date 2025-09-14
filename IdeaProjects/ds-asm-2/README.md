# Tool used
1. Java SDK 24
2. IntelliJ IDE Community Edition
3. Gson 2.11.0 (make sure to add from Maven Repo)
    - Go to Project Structure -> Module -> Click on Add or + if not there
    - Search and add this: firstbird.backbone.gson_2.11
    - Search and add this: com.google.code.gson:gson:2.11.0
    - gson-2.11.0.jar (Download link: https://repo1.maven.org/maven2/com/google/code/gson/gson/2.11.0/)

# Note
1. GET client gets the newest weather data from each content server
2. Content Server + Client update their onw clocks from the Agg Sv send back lamport timestamp
3. If you have done some **manual** testing and would like a restart, please restore the feed.json to this [] and clear the updates.wal
4. source_id was used as key when updating Agg Sv memory as we want the latest record from each content server 
5. Incase port not release -> netstat -ano | findstr :4567 then taskkill /PID <PID> /F
# Compile All Files
javac -cp "out;gson-2.11.0.jar" -d out src/main/java/org/example/*.java

# Aggregation Server
Windows:
```bash
java -cp "out;gson-2.11.0.jar" org.example.AggregationServer 
```

# Content Server
1. javac -cp "gson-2.11.0.jar" -d out src/main/java/org/example/ContentServer.java

Start with default weather record (sample.json)
If you want to start content server with a different weather record, please change the 
cs-data/sample.json in the below command to <your_new_file_location> or use the interactive menu.
(I have made several files for testing, all of them can be found in the dir **cs-data/**)

```bash 
java -cp "out;gson-2.11.0.jar" org.example.ContentServer http://localhost:4567/weather.json cs-data/sample.txt 1
```
(You can also choose option 2 in the menu after running this command to change the weather record you want to send)

Instruction:
1. Start up content server with the above java command
2. By default, your content server will read from cs-data/sample.json
3. To send a different weather json, please choose 2 and specify the location of that file (eg: cs-data/sample2.txt)
4. Choose 3 if you want to exit the program 

Workflow:
1. Increment Lamport before sending PUT request
2. Send PUT + Lamport to Aggregation Server
3. Server sends updated Lamport back
4. Content Server caches response and update its own Lamport again

# GET Client
1.  java -cp "out;gson-2.11.0.jar" org.example.GETClient  http://localhost:4567/weather.json

Workflow:
1. Increment Lamport before sending GET request
2. Send GET + Lamport to Aggregation Server
3. Server sends updated Lamport back
4. Client caches response and update its own Lamport again
5. Display the weather data from the Aggregation Server response

# Functionality Checklist:
**Agg Sv**: 
1. Remove content from Out of Contact Server after 30s - DONE
2. GETClient get result from Agg Sv - DONE
3. Support multiple content servers sending data simultaneously - DONE
4. PUT -> GET -> PUT is not yet handled, only PUT1 -> PUT2 (Best to use Reentrant lock for this) - DOING
5. Agg Sv goes down, then up (Client can read weather data before the crash) - DONE
6. Send ACK back to Content Server + Client with LAMPORT - DONE 

**HTTP server code**
1. 200 (Content Server + Client) - DONE
2. 201 (content server) - DONE
3. 204 - Done
4. 400 - DONE
5. 500 - Done

**Content-Server**:
1. Content Server send + Agg Sv accept - DONE
2. Expiry checker work on multiple content servers - DONE
3. Retry mechanism when a content server tries to send data, but the aggregation server is down - DONE

**Client**:
1. Get weather data from Agg Sv - DONE
2. Update its own Lamport Clock - DONE

**Crash Recovery**
1. Sv down then up -> Content Sv resend return with 200 - DONE
2. Expiry Checker doesn't work after server crash -> need content server to send again - DOING
3. updates.wal didn't remove old records for out-of-contact servers (but GET does rec empty response from Agg Sv) - DOING 

# Automated Testing 
Compile the test file with
```bash
javac -d out -cp "gson-2.11.0.jar;junit-platform-console-standalone-1.9.3.jar;out" src/test/AggregationServerTest.java
```

Now run the test class (run all test)
```bash
java -jar junit-platform-console-standalone-1.9.3.jar -cp "out;gson-2.11.0.jar" --scan-class-path

```
# How Do We Handle Concurrency?
When 2 requests come at the same time:
- The order of arrival is non-deterministic
- It depends on the OS to decide which hit the Agg Sv first but this is not reliable
- Even if we have a queue to store [ PUT1, PUT2 ], PUT2 might get overwritten by PUT1 if we don't check the Lamport clock 

# Race Condition and Deadlock
Use Reentrant clock for protecting critical sections:
1. apply_put()
2. last_update
3. start_expiry_checker()
4. handle GET request

# How Do We Handle Crash and Recovery
When the Aggregation Server starts:
- Load the snapshot via read_snapshot().
- Replay WAL via replay_wal().
- Build the full in-memory state.

When handling a PUT request:
- Call append_WAL() → Log the update.
- Update the in-memory store.
- Periodically call write_snapshot() to save everything. (being handled by expiry_checker in agg sv)
