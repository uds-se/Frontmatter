### Frontmatter
Frontmatter is a context-sensitive static analysis tool which extracts UI hierarchies and app's behavior from Android applications.

### Building tribble
Building and running Frontmatter requires Java version 11 or greater.  
At first, install and patch necessary dependencies by running `./gradle installDependencies` in the project's root directory.  
Next, build tribble by running `./gradle build` in the project's root directory.  
To get a fat jar, which can be run standalone, run `./gradle shadowJar`. 

### Running Frontmatter
When the build completes, there should be a runnable jar file frontmatter-1.0.93-SNAPSHOT.jar located in ./build/libs.
Executing java -jar frontmatter-1.0.93-SNAPSHOT.jar --help will print out all available flags and options.

#### UI analysis
UI hierarchies can be mined with the following command:  
`java -Xmx40g -Xss4m -jar frontmatter-1.0.93-SNAPSHOT.jar ui-analysis -i sample.apk -u ./results/ui/sample.json`

#### API calls
Program behavior, i.e. API calls from UI elements, can be collected with:  
`java -Xmx40g -Xss4m -jar frontmatter-1.0.93-SNAPSHOT.jar api-analysis -i sample.apk -u ./results/ui/sample.json -a ./results/api/sample.json`
Note that the result of UI analysis (./results/ui/sample.json) should exist.

### Processing apps in bulk
frontmatter_luigi folder contains scripts to run Frontmatter analysis in parallel on a big corpus of apks. 
They can be also used to download and process apps from Androzoo.

### Parsing results into a table representation
parsers folder contains scripts to parse UI hierarchies into a flat representation.
Use parse_ui_to_widgets.py to collect data of particular widgets and parse_ui_to_activity.py to get the content of whole activities:  
`python parse_ui_to_widgets.py  -d ./results/ui -a ../results/api --ui widgets.csv --api apis.csv -p -s stats.json`  
Run `'python parse_ui_to_widgets.py --help` for additional params.