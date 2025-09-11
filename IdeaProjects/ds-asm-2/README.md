# Tool used
1. Java SDK 24
2. Gson 2.11.0 (make sure to add from Maven Repo)
    - Go to Project Structure -> Module -> Click on Add or + if not there
    - Search and add this: firstbird.backbone.gson_2.11
    - Search and add this: com.google.code.gson:gson:2.11.0
# Note
1. When GET client ask for weather data -> Multiple records sent back by Agg Sv
2. PUT -> GET -> PUT is not yet handled, only PUT1 -> PUT2 (Best to use Reentrant lock for this)

# How Do We Handle Crash and Recovery
When the Aggregation Server starts:
- Load the snapshot via read_snapshot().
- Replay WAL via replay_wal().
- Build the full in-memory state.

When handling a PUT request:
- Call append_WAL() → Log the update.
- Update the in-memory store.
- Periodically call write_snapshot() to save everything. (being handled by expiry_checker in agg sv)

# Compile All Files
javac -cp "out;gson-2.11.0.jar" -d out src/main/java/org/example/*.java

# Content Server
1. javac -cp "gson-2.11.0.jar" -d out src/main/java/org/example/ContentServer.java
2. java -cp "out;gson-2.11.0.jar" org.example.ContentServer http://localhost:4567/weather.json cs-data/sample.json


# Aggregation Server
1. java -cp "out;gson-2.11.0.jar" org.example.AggregationServer (window)