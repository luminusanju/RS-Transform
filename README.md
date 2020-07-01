# GDSN Transformation Template

## Prerequisites to Build

1. Install the Java Platform 1.8 (JDK): http://www.oracle.com/technetwork/java/javase/downloads/index.html
2. Ensure the `JAVA_HOME` environment variable points to your JDK installation
  * Start a bash shell; on CentOS, this is the default shell used by the Terminal application
  * Run the command: `env | grep JAVA`
  * You should see a line like: `JAVA_HOME=/usr/lib/jvm/java-1.8.0-openjdk-1.8.0.101-3.b13.el7_2.x86_64`
  * If not, add it to your .bashrc file as follows:
    * to get the base path of the JDK, run the command: `readlink -ze /usr/bin/javac | xargs -0 dirname -z | xargs -0 dirname`
    * edit the .bashrc file: `vi ~/.bashrc`
    * move to the end of the file with the arrow keys and press the "a" key (which puts vi into append mode); start a new line after the comment `# User specific aliases and functions`
    * enter the text: `export JAVA_HOME=/usr/lib/jvm/java-1.8.0-openjdk-1.8.0.101-3.b13.el7_2.x86_64` (or whatever the appropriate path is)
    * add another line: `export PATH=$PATH:$JAVA_HOME/bin`
    * press the `Esc` key, followed by `:wq` (which writes your changes and quits vi)
    * reload the .bashrc file: `source ~/.bashrc`
3. Install Apache Maven: https://maven.apache.org/install.html
4. Add the Maven `bin` directory to your `PATH` environment variable. See the above example of updating the path by editing the .bashrc file. For example, your .bashrc file might now contain:
  * `export PATH=$PATH:$JAVA_HOME/bin:/opt/apache-maven-3.3.9/bin`

## Building the Project

Note: All commands must be run on the solution maven project.

1. Refer connectors/settings.xml
  * `Add you git user name and access token`
  * `Copy the settings.xml to ~/.m2`
2. Change directory to the solution project:
  * `cd ~/git/gdsn-translation-app-template/connectors`
3. Clean up all directories:
  * `mvn clean`
4. Compile the projects:
  * `mvn compile`
5. Package the jars according to each individual settings in each module level project:
  * `mvn package`
6. To get the package without running tests:
  * `mvn package -DskipTests`

## Documentation

1. https://riversand.atlassian.net/wiki/spaces/RP/pages/1226605554/GDSN+Customer+Model+Transform+Design
