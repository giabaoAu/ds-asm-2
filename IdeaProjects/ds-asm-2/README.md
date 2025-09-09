# Tool used
1. Java SDK 24
2. Gson 2.11.0 (make sure to add from Maven Repo)
    - Go to Project Structure -> Module -> Click on Add or + if not there
    - Search and add this: firstbird.backbone.gson_2.11
    - Search and add this: com.google.code.gson:gson:2.11.0
# Note
1. When GET client ask for weather data -> Multiple records sent back by Agg Sv
2. PUT -> GET -> PUT is not yet handled, only PUT1 -> PUT2 (Best to use Reentrant lock for this)