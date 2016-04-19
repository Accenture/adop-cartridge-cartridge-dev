// Folders
def workspaceFolderName = "${WORKSPACE_NAME}"
def projectFolderName = "${PROJECT_NAME}"

// Jobs
def createNewCartridgeJob = freeStyleJob(projectFolderName + "/CreateNewCartridge")
 
 // Setup Job 
 createNewCartridgeJob.with{
    parameters{
            stringParam("BASE_CARTRIDGE","https://github.com/Accenture/adop-cartridge-skeleton.git","Git URL of the cartridge you want to base the new cartridge on.")
            stringParam("NEW_CARTRIDGE","my-new-cartridge","Name for your new cartridge. This will be automatically namespaced using the current Workspace and Project name.")
    }
    environmentVariables {
        env('WORKSPACE_NAME',workspaceFolderName)
        env('PROJECT_NAME',projectFolderName)
    }
    scm {
            git{
                remote{
                    name("origin")
                    url('${BASE_CARTRIDGE}')
                    credentials("adop-jenkins-master")
                }
                branch("*/master")
            }
    }
    wrappers {
        preBuildCleanup()
        injectPasswords()
        maskPasswords()
        sshAgent("adop-jenkins-master")
    }
    steps {
        shell('''#!/bin/bash -ex

# Create Gerrit repository
target_repo_name="${PROJECT_NAME}/${NEW_CARTRIDGE}"
repo_exists=0
list_of_repos=$(ssh -n -o StrictHostKeyChecking=no -p 29418 jenkins@gerrit gerrit ls-projects --type code)

for repo in ${list_of_repos}
do
  if [ ${repo} = ${target_repo_name} ]; then
    echo "Found: ${repo}"
    repo_exists=1
    break
  fi
done

if [ ${repo_exists} -eq 0 ]; then
  ssh -n -o StrictHostKeyChecking=no -p 29418 jenkins@gerrit gerrit create-project --parent "All-Projects" "${target_repo_name}"
else
  echo "Repository already exists, skipping: ${target_repo_name}"
  exit 1
fi

# Setup remote & populate
git remote add adop ssh://jenkins@gerrit:29418/"${target_repo_name}"
git fetch adop
git push adop +refs/remotes/origin/*:refs/heads/*

set +x
echo
echo ALL FINISHED!
echo You can now go to Gerrit and clone the Git repo for your new ${target_repo_name} cartridge!
echo
''')
    }
    
 }
