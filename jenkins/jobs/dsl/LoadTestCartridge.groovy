import pluggable.scm.*;

SCMProvider scmProvider = SCMProviderHandler.getScmProvider("${SCM_PROVIDER_ID}", binding.variables)

// Folders
def workspaceFolderName = "${WORKSPACE_NAME}"
def projectFolderName = "${PROJECT_NAME}"
def projectScmNamespace = "${SCM_NAMESPACE}"

// Jobs
def loadCartridgeJob = freeStyleJob(projectFolderName + "/LoadDevCartridge")

def cartGitRepo = "adop-cartridge-skeleton";
def platformToolGitRepo = "platform-management";

// Setup Load_Cartridge
loadCartridgeJob.with{
    parameters{
        stringParam("CARTRIDGE_CLONE_URL", 'ssh://git@gitlab/${projectScmNamespace}/${cartGitRepo}.git', "Cartridge URL to load")
        // Embedded script to determine available SCM providers
        extensibleChoiceParameterDefinition {
          name('SCM_PROVIDER')
          choiceListProvider {
            systemGroovyChoiceListProvider {
                groovyScript {
                    script('''
import hudson.model.*;
import hudson.util.*;

base_path = "/var/jenkins_home/userContent/datastore/pluggable/scm"

// Initialise folder containing all SCM provider properties files
String PropertiesPath = base_path + "/ScmProviders/"
File folder = new File(PropertiesPath)
def providerList = []

// Loop through all files in properties data store and add to returned list
for (File fileEntry : folder.listFiles()) {
  if (!fileEntry.isDirectory()){
    String title = PropertiesPath +  fileEntry.getName()
    Properties scmProperties = new Properties()
    InputStream input = null
    input = new FileInputStream(title)
    scmProperties.load(input)
    String url = scmProperties.getProperty("scm.url")
    String protocol = scmProperties.getProperty("scm.protocol")
    String id = scmProperties.getProperty("scm.id")
    String output = url + " - " + protocol + " (" + id + ")"
    providerList.add(output)
  }
}

if (providerList.isEmpty()) {
    providerList.add("No SCM providers found")
}

return providerList;
''')
                                    sandbox(true)
                }
              defaultChoice('Top')
              usePredefinedVariables(false)
            }
          }
          editable(false)
          description('Your chosen SCM Provider and the appropriate cloning protocol')
        }
    }
    environmentVariables {
        groovy("return [SCM_KEY: org.apache.commons.lang.RandomStringUtils.randomAlphanumeric(20)]")
        env('WORKSPACE_NAME',workspaceFolderName)
        env('PROJECT_NAME',projectFolderName)
        keepBuildVariables(true)
        keepSystemVariables(true)
        overrideBuildParameters(true)
    }
    wrappers {
        preBuildCleanup()
        injectPasswords()
        maskPasswords()
        credentialsBinding {
            file('SCM_SSH_KEY', 'adop-jenkins-private')
        }
        copyToSlaveBuildWrapper {
          includes("**/**")
          excludes("")
          flatten(false)
          includeAntExcludes(false)
          relativeTo('''${JENKINS_HOME}/userContent''')
          hudsonHomeRelative(false)
        }
    }
    label("!master && !windows && !ios")
    steps {
        shell('''#!/bin/bash -ex

mkdir ${WORKSPACE}/tmp

# Output SCM provider ID to a properties file
echo SCM_PROVIDER_ID=$(echo ${SCM_PROVIDER} | cut -d "(" -f2 | cut -d ")" -f1) > ${WORKSPACE}/scm.properties

echo "SCM_NAMESPACE not specified, setting to PROJECT_NAME..."
SCM_NAMESPACE="${PROJECT_NAME}"

echo SCM_NAMESPACE=$(echo ${SCM_NAMESPACE} | cut -d "(" -f2 | cut -d ")" -f1) >> ${WORKSPACE}/scm.properties
''')
        environmentVariables {
            propertiesFile('${WORKSPACE}/scm.properties')
        }
        systemGroovyCommand('''
import com.cloudbees.plugins.credentials.*;
import com.cloudbees.plugins.credentials.common.*;
import pluggable.scm.PropertiesSCMProviderDataStore;
import pluggable.scm.SCMProviderDataStore;
import pluggable.configuration.EnvVarProperty;
import pluggable.scm.helpers.PropertyUtils;
import java.util.Properties;
import hudson.FilePath;

println "[INFO] - Attempting to inject SCM provider credentials. Note: Not all SCM provider require a username/password combination."

String scmProviderId = build.getEnvironment(listener).get('SCM_PROVIDER_ID');

EnvVarProperty envVarProperty = EnvVarProperty.getInstance();
envVarProperty.setVariableBindings(
  build.getEnvironment(listener));

SCMProviderDataStore scmProviderDataStore = new PropertiesSCMProviderDataStore();
Properties scmProviderProperties = scmProviderDataStore.get(scmProviderId);

String credentialId = scmProviderProperties.get("loader.credentialId");

if(credentialId != null){

  if(credentialId.equals("")){
    println "[WARN] - load.credentialId property provided but is an empty string. SCM providers that require a username/password may not behave as expected.";
    println "[WARN] - Credential secret file not created."
  }else{
    def username_matcher = CredentialsMatchers.withId(credentialId);
    def available_credentials = CredentialsProvider.lookupCredentials(StandardUsernameCredentials.class);

    def credential = CredentialsMatchers.firstOrNull(available_credentials, username_matcher);

    if(credential == null){
      println "[WARN] - Credential with id " + credentialId + " not found."
      println "[WARN] - SCM providers that require a username/password may not behave as expected.";
      println "[WARN] - Credential secret file not created."
    }else{
      credentialInfo = [credential.username, credential.password];

      channel = build.workspace.channel;
      filePath = new FilePath(channel, build.workspace.toString() + "@tmp/secretFiles/" + build.getEnvVars()["SCM_KEY"]);
      filePath.write("SCM_USERNAME="+credentialInfo[0]+"\\nSCM_PASSWORD="+credentialInfo[1], null);

      println "[INFO] - Credentials injected."
    }
  }
}else{
  println "[INFO] - No credential to inject. SCM provider load.credentialId property not found."
}
'''){
  classpath('${PLUGGABLE_SCM_PROVIDER_PATH}')
}
        shell('''#!/bin/bash -ex

# We trust everywhere
echo -e "#!/bin/sh
exec ssh -i ${SCM_SSH_KEY} -o StrictHostKeyChecking=no \"\\\$@\"
" > ${WORKSPACE}/custom_ssh
chmod +x ${WORKSPACE}/custom_ssh
export GIT_SSH="${WORKSPACE}/custom_ssh"

# Clone Cartridge
echo "INFO: cloning ${CARTRIDGE_CLONE_URL}"
# we do not want to show the password
set +x
if ( [ ${CARTRIDGE_CLONE_URL%://*} == "https" ] ||  [ ${CARTRIDGE_CLONE_URL%://*} == "http" ] ) && [ -f ${WORKSPACE}/${SCM_KEY} ]; then
    source ${WORKSPACE}/${SCM_KEY}
    git clone ${CARTRIDGE_CLONE_URL%://*}://${SCM_USERNAME}:${SCM_PASSWORD}@${CARTRIDGE_CLONE_URL#*://} cartridge
else
    git clone ${CARTRIDGE_CLONE_URL} cartridge
fi
set -x

# Find the cartridge
export CART_HOME=$(dirname $(find -name metadata.cartridge | head -1))
echo "CART_HOME=${CART_HOME}" > ${WORKSPACE}/carthome.properties

# Output SCM provider ID to a properties file
echo GIT_SSH="${GIT_SSH}" >> ${WORKSPACE}/scm_provider.properties

# Provision one-time infrastructure
if [ -d ${WORKSPACE}/${CART_HOME}/infra ]; then
    cd ${WORKSPACE}/${CART_HOME}/infra
    if [ -f provision.sh ]; then
        source provision.sh
    else
        echo "INFO: ${CART_HOME}/infra/provision.sh not found"
    fi
fi

# Generate Jenkins Jobs
if [ -d ${WORKSPACE}/${CART_HOME}/jenkins/jobs ]; then
    cd ${WORKSPACE}/${CART_HOME}/jenkins/jobs
    if [ -f generate.sh ]; then
        source generate.sh
    else
        echo "INFO: ${CART_HOME}/jenkins/jobs/generate.sh not found"
    fi
fi
''')
        environmentVariables {
          propertiesFile('${WORKSPACE}/carthome.properties')
        }
        environmentVariables {
          propertiesFile('${WORKSPACE}/scm_provider.properties')
        }
        systemGroovyCommand('''
import jenkins.model.*;
import groovy.io.FileType;
import hudson.FilePath;

def jenkinsInstace = Jenkins.instance;
def projectName = build.getEnvironment(listener).get('PROJECT_NAME');
def cartHome = build.getEnvironment(listener).get('CART_HOME');
def workspace = build.workspace.toString();
def cartridgeWorkspace = workspace + '/' + cartHome + '/jenkins/jobs/xml/';
def channel = build.workspace.channel;
FilePath filePath = new FilePath(channel, cartridgeWorkspace);
List<FilePath> xmlFiles = filePath.list('**/*.xml');

xmlFiles.each {
  File configFile = new File(it.toURI());

  String configXml = it.readToString();

  ByteArrayInputStream xmlStream = new ByteArrayInputStream(
    configXml.getBytes());

  String jobName = configFile.getName()
      .substring(0,
                   configFile
                   .getName()
                    .lastIndexOf('.'));

  jenkinsInstace.getItem(projectName,jenkinsInstace)
    .createProjectFromXML(jobName, xmlStream);

  println '[INFO] - Imported XML job config: ' + it.toURI();
}
''')
        environmentVariables {
          env('PLUGGABLE_SCM_PROVIDER_PATH','${WORKSPACE}/job_dsl_additional_classpath/')
          env('PLUGGABLE_SCM_PROVIDER_PROPERTIES_PATH','${WORKSPACE}/datastore/pluggable/scm')
        }
        groovy {
          scriptSource {
            stringScriptSource {
              command('''
import pluggable.scm.SCMProvider;
import pluggable.scm.SCMProviderHandler;
import pluggable.configuration.EnvVarProperty;

EnvVarProperty envVarProperty = EnvVarProperty.getInstance();
envVarProperty.setVariableBindings(System.getenv());

String scmProviderId = envVarProperty.getProperty('SCM_PROVIDER_ID')

SCMProvider scmProvider = SCMProviderHandler.getScmProvider(scmProviderId, System.getenv())

def workspace = envVarProperty.getProperty('WORKSPACE')
def projectFolderName = envVarProperty.getProperty('PROJECT_NAME')

def cartridgeFolder = '';
def scmNamespace = '';

// Checking if the parameters have been set and they exist within the env properties
if (envVarProperty.hasProperty('OVERWRITE_REPOS')){
  overwriteRepos = envVarProperty.getProperty('OVERWRITE_REPOS')
}else{
  overwriteRepos = ''
}
if (envVarProperty.hasProperty('ENABLE_CODE_REVIEW')){
  codeReviewEnabled = envVarProperty.getProperty('ENABLE_CODE_REVIEW')
}else{
  codeReviewEnabled = ''
}
if (envVarProperty.hasProperty('CARTRIDGE_FOLDER')){
  cartridgeFolder = envVarProperty.getProperty('CARTRIDGE_FOLDER')
}else{
  cartridgeFolder = ''
}
if (envVarProperty.hasProperty('SCM_NAMESPACE')){
  scmNamespace = envVarProperty.getProperty('SCM_NAMESPACE')
}else{
  scmNamespace = ''
}

String repoNamespace = null;

if (scmNamespace != null && !scmNamespace.isEmpty()){
  println("Custom SCM namespace specified...")
  repoNamespace = scmNamespace
} else {
  println("Custom SCM namespace not specified, using default project namespace...")
  if (cartridgeFolder == ""){
    println("Folder name not specified...")
    repoNamespace = projectFolderName
  } else {
    println("Folder name specified, changing project namespace value..")
    repoNamespace = projectFolderName + "/" + cartridgeFolder
  }
}

scmProvider.createScmRepos(workspace, repoNamespace, codeReviewEnabled, overwriteRepos)
''')
                }
            }
            parameters("")
            scriptParameters("")
            properties("")
            javaOpts("")
            groovyName("ADOP Groovy")
            classPath('''${WORKSPACE}/job_dsl_additional_classpath''')
        }
        environmentVariables {
          env('PLUGGABLE_SCM_PROVIDER_PATH','${JENKINS_HOME}/userContent/job_dsl_additional_classpath/')
          env('PLUGGABLE_SCM_PROVIDER_PROPERTIES_PATH','${JENKINS_HOME}/userContent/datastore/pluggable/scm')
          propertiesFile('${WORKSPACE}/scm.properties')
        }
        dsl {
            external("cartridge/**/jenkins/jobs/dsl/*.groovy")
            additionalClasspath("job_dsl_additional_classpath")
        }
    }
    scm scmProvider.get(projectScmNamespace, "${platformToolGitRepo}", "*/master", "adop-jenkins-master", null)
}
