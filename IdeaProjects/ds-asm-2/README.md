# Tool used
1. Java SDK 24
2. Gson 2.11.0 (make sure to add from Maven Repo)
    - Go to Project Structure -> Module -> Click on Add or + if not there
    - Search and add this: firstbird.backbone.gson_2.11
    - Search and add this: com.google.code.gson:gson:2.11.0
    - gson-2.11.0.jar (Download link: https://repo1.maven.org/maven2/com/google/code/gson/gson/2.11.0/)

# Note
1. When GET client ask for weather data -> Multiple records sent back by Agg Sv
2. Content Server + Client update from Agg Sv send back lamport timestamp

# Compile All Files
javac -cp "out;gson-2.11.0.jar" -d out src/main/java/org/example/*.java

# Content Server
1. javac -cp "gson-2.11.0.jar" -d out src/main/java/org/example/ContentServer.java
2. java -cp "out;gson-2.11.0.jar" org.example.ContentServer http://localhost:4567/weather.json cs-data/sample.json

# GET Client
1.  java -cp "out;gson-2.11.0.jar" org.example.GETClient  http://localhost:4567/weather.json

# Aggregation Server
- java -cp "out;gson-2.11.0.jar" org.example.AggregationServer (window)

# Functionality Checklist:
**Agg Sv**: 
1. remove content from Out of Contact Server after 30s - DONE
2. GETClient get result from Agg Sv - DONE
3. Support multiple content servers sending data simultaneously - DONE
4. PUT -> GET -> PUT is not yet handled, only PUT1 -> PUT2 (Best to use Reentrant lock for this) - DOING
5. Agg Sv goes down, then up (Client still can read weather data after agg sv on again but expiry_check not working - DOING
6. Send ACK back to Content Server + Client with LAMPORT - DOING 

**HTTP server code**
1. 200 (Content Server + Client) - DONE
2. 201 (content server) - DONE
3. 204 
4. 400
5. 500

**Content-Server**:
1. Content Server send + Agg Sv accept - DONE
2. Fault-tolerant refers to the retry mechanism when a content server tries to send data, but the aggregation server is down - DOING
3. Expiry checker work on multiple content servers - DONE

**Client**:
1. Get weather data from Agg Sv - DONE
2. Update its own Lamport Clock - DONE

**Crash Recovery**
1. Sv down then up -> Content Sv send again but got 201 instead of 201 
2. Expiry Checker doesn't work after server crash -> need content server to send again
3. updates.wal didn't remove old records for out-of-contact servers (but GET does rec empty reponse from Agg Sv)

# Automated Testing 

# How Do We Handle Crash and Recovery
When the Aggregation Server starts:
- Load the snapshot via read_snapshot().
- Replay WAL via replay_wal().
- Build the full in-memory state.

When handling a PUT request:
- Call append_WAL() → Log the update.
- Update the in-memory store.
- Periodically call write_snapshot() to save everything. (being handled by expiry_checker in agg sv)
