package com.saltstack.jenkins

import java.util.*
import java.util.logging.Level
import java.util.logging.Logger
import groovy.json.*
import hudson.Functions
import hudson.model.User
import hudson.model.Cause
import hudson.model.Result
import hudson.model.AbstractProject
import jenkins.model.Jenkins
import jenkins.security.ApiTokenProperty
import java.io.InputStreamReader
import org.kohsuke.github.GHEvent
import org.kohsuke.github.GitHub
import org.kohsuke.github.GHRepository
import org.kohsuke.github.GHIssueState
import org.kohsuke.github.GHCommitState
import org.kohsuke.github.Requester
import com.cloudbees.jenkins.GitHubRepositoryName

import com.saltstack.jenkins.GitHubMarkup


class Project {
    String name;
    String display_name;
    String repo;
    Boolean setup_push_hooks = true;
    Boolean create_branches_webhook = false;
    Boolean set_commit_status = true;
    Boolean trigger_new_prs = true;

    protected static String webhooks_user = 'salt-testing';

    private GitHub _github;
    private GHRepository _repo;
    private Boolean _authenticated;

    private static final Logger LOGGER = Logger.getLogger(Project.class.getName());

    Project() {
        this.name;
        this.display_name;
        this.repo;
        this.setup_push_hooks = true;
        this.create_branches_webhook = false;
        this.set_commit_status = true;
        this.trigger_new_prs = true;
        this._repo = null
        this._github = null
    }

    def GHRepository getRepository() {
        if ( this._github == null ) {
            try {
                this._github = GitHub.connect()
                this._authenticated = true
            } catch (Throwable e2) {
                this._github = GitHub.connectAnonymously()
                this._authenticated = false
            }
        }
        if ( this._repo == null ) {
            this._repo = this._github.getRepository(this.repo)
        }
        return this._repo
    }

    def GHRepository getAuthenticatedRepository() {
        if ( this._repo == null ) {
            try {
                def github_repo_url = "https://github.com/${this.repo}"
                this._repo = GitHubRepositoryName.create(github_repo_url).resolve().iterator().next()
            } catch (Throwable e1) {
                LOGGER.log(
                    Level.SEVERE,
                    "Failed to load authenticated repository using GitHubRepositoryName.create()",
                    e1
                )
                return null
            }
        }
        return this._repo
    }

    def resetRepoGitHub() {
        LOGGER.fine("Reseting cached _repo and _github instances")
        this._repo = null
        this._github = null
    }

    def toMap(Boolean include_branches = false, Boolean include_prs = false) {
        def data = [
            name: this.name,
            display_name: this.display_name,
            description: this.getRepositoryDescription(),
            repo: this.repo,
            setup_push_hooks: this.setup_push_hooks,
            create_branches_webhook: this.create_branches_webhook,
            set_commit_status: this.set_commit_status,
            trigger_new_prs: this.trigger_new_prs
        ]
        if ( include_branches ) {
            data['branches'] = this.getRepositoryBranches()
        }
        if ( include_prs ) {
            data['pull_requests'] = this.getOpenPullRequests()
        }
        return data
    }

    def toJSON(Boolean include_branches = false, Boolean include_prs = false) {
        return new JsonBuilder(this.toMap(include_branches, include_prs)).toString()
    }


    def getRepositoryDescription() {
        if ( getAuthenticatedRepository() != null ) {
            return getAuthenticatedRepository().getDescription()
        }
        return null
    }

    def getRepositoryBranches() {
        def branches = []
        if ( getAuthenticatedRepository() != null ) {
            getAuthenticatedRepository().getBranches().each { branch_name, branch_data ->
                branches.add(branch_name)
            }
        }
        return branches
    }

    def setCommitStatusPre(currentBuild, commit_status_context, out) {
        if ( this.set_commit_status != true ) {
            out.println "Not setting commit status for ${this.display_name} because it's currently disabled"
            return
        }
        out.println "Setting Initial Commit Status to the current build"

        def build_env_vars = currentBuild.getEnvironment()
        def result = currentBuild.getResult()

        def state = GHCommitState.ERROR;

        if (result == null) { // Build is ongoing
            state = GHCommitState.PENDING;
            out.println 'GitHub commit status is PENDING'
        } else if (result.isBetterOrEqualTo(Result.SUCCESS)) {
            state = GHCommitState.SUCCESS;
            out.println 'GitHub commit status is SUCCESS'
        } else if (result.isBetterOrEqualTo(Result.UNSTABLE)) {
            state = GHCommitState.FAILURE;
            out.println 'GitHub commit status is FAILURE'
        } else {
            out.println 'GitHub commit status is ERROR'
        }

        def git_commit = build_env_vars.get('GIT_COMMIT', build_env_vars.get('ghprbActualCommit'))
        if ( git_commit != null ) {
            def status_result = this.getAuthenticatedRepository().createCommitStatus(
                git_commit,
                state,
                currentBuild.getAbsoluteUrl(),
                currentBuild.getFullDisplayName(),
                commit_status_context
            )
            if ( ! status_result ) {
                out.println "Failed to set commit status on GitHub for ${this.repo}@${git_commit}"
                manager.listener.logger.println msg
            } else {
                out.println "GitHub commit status successfuly set for for ${this.repo}@${git_commit}"
            }
        } else {
            out.println 'No git commit SHA information could be found. Not setting final commit status information.'
        }
    }

    def setCommitStatusPost(manager) {
        if ( this.set_commit_status != true ) {
            manager.listener.logger.println "Not setting commit status for ${this.display_name} because it's currently disabled"
            return
        }
        manager.listener.logger.println "Setting Commit Status to the current build result"

        def result = manager.build.getResult()

        def commit_status_context = manager.envVars.get('COMMIT_STATUS_CONTEXT', 'ci')
        manager.listener.logger.println "GitHub commit status context: ${commit_status_context}"

        def state = GHCommitState.ERROR;

        if (result == null) { // Build is ongoing
            state = GHCommitState.PENDING;
            manager.listener.logger.println 'GitHub commit status is PENDING'
        } else if (result.isBetterOrEqualTo(Result.SUCCESS)) {
            state = GHCommitState.SUCCESS;
            manager.listener.logger.println 'GitHub commit status is SUCCESS'
        } else if (result.isBetterOrEqualTo(Result.UNSTABLE)) {
            state = GHCommitState.FAILURE;
            manager.listener.logger.println 'GitHub commit status is FAILURE'
        } else {
            manager.listener.logger.println 'GitHub commit status is ERROR'
        }

        def git_commit = manager.envVars.get('GIT_COMMIT', manager.envVars.get('ghprbActualCommit'))
        if ( git_commit != null ) {
            def status_result = this.getAuthenticatedRepository().createCommitStatus(
                git_commit,
                state,
                manager.build.getAbsoluteUrl(),
                manager.build.getFullDisplayName(),
                commit_status_context
            )
            if ( ! status_result ) {
                msg = "Failed to set commit status on GitHub for ${this.repo}@${git_commit}"
                manager.addWarningBadge(msg)
                manager.listener.logger.println msg
            } else {
                msg = "GitHub commit status successfuly set for for ${this.repo}@${git_commit}"
                manager.addInfoBadge(msg)
                manager.listener.logger.println(msg)
            }
        } else {
            msg = 'No git commit SHA information could be found. Not setting final commit status information.'
            manager.listener.logger.println(msg)
        }
    }

    def getOpenPullRequests() {
        def prs = []
        if ( this.getAuthenticatedRepository() != null ) {
            def gh_auth_user = this.getAuthenticatedRepository().root.login
            def gh_auth_token = this.getAuthenticatedRepository().root.encodedAuthorization.split()[-1]
            this.getAuthenticatedRepository().getPullRequests(GHIssueState.OPEN).each { pr ->
                println '  * Processing PR #' + pr.number
                prs.add([
                    number: pr.number,
                    title: pr.title,
                    url: pr.getHtmlUrl(),
                    body: pr.body,
                    rendered_description: """
                        <h3>
                            <img src="${hudson.Functions.getResourcePath()}/plugin/github/logov3.png"/>
                            <a href="${pr.url}" title="${pr.title}" alt="${pr.title}">#${pr.number}</a>
                            &mdash;
                            ${pr.title}
                        <h3>
                        <br/>
                        ${GitHubMarkup.toHTML(pr.body, this.repo, gh_auth_user, gh_auth_token)}
                        """.stripIndent(),
                    sha: pr.getHead().getSha(),
                    repo: this.repo
                ])
            }
        } else {
            println "Unable to load the authenticated repository instance for ${this.display_name}"
        }

        return prs
    }

    def triggerPullRequestSeedJob(manager) {
        try {
            def job = Jenkins.instance.getJob(this.name).getJob('pr').getJob('jenkins-seed')
            if ( job != null ) {
                job.scheduleBuild(new Cause.UserIdCause())
            } else {
                manager.listener.logger.println "Failed to trigger Pull Requests seed job for ${this.display_name}. Not found..."
            }
        } catch ( Throwable e ) {
            manager.listener.logger.println "Failed to trigger Pull Requests seed job for ${this.display_name}: ${e.toString()}"
        }
    }

    def cleanOldPullRequests(manager, howOld=7) {
        def prs_folder = Jenkins.instance.getJob(this.name).getJob('pr')
        if ( prs_folder == null ) {
            return
        }
        for ( folder in prs_folder.getItems() ) {
            if ( folder.class.canonicalName != 'com.cloudbees.hudson.plugins.folder.Folder' ) {
                continue
            }
            manager.listener.logger.println "Processing ${folder.getFullDisplayName()}"
            def folder_main_build = folder.getJob('main-build')
            if ( folder_main_build == null ) {
                // The folder exists but the main build does not!? Delete the folder
                manager.listener.logger.println "Did not find a main-build job for folder ${folder.getFullDisplayName()}. Deleting folder"
                folder.delete()
                folder.save()
                continue
            }
            if ( ! folder_main_build.disabled ) {
                manager.listener.logger.println "The ${folder_main_build.getFullDisplayName()} job is not disabled. Continuing..."
                continue
            }
            // We only delete folders with disabled jobs, which are closed/merged pull requests
            def last_build = folder_main_build.getLastBuild()
            if ( last_build == null ) {
                manager.listener.logger.println "No last build for ${folder_main_build.getFullDisplayName()}"
                continue
            }
            manager.listener.logger.println "Grabbing last build timestamp for ${folder_main_build.getFullDisplayName()}"
            def last_build_date = folder_main_build.getLastBuild().timestamp.clone()
            last_build_date.add(GregorianCalendar.DATE, howOld)
            def current_date = new GregorianCalendar();
            current_date.set(Calendar.HOUR_OF_DAY, 0);
            current_date.set(Calendar.MINUTE, 0);
            current_date.set(Calendar.SECOND, 0);
            if ( last_build_date < current_date ) {
                manager.listener.logger.println "Last build of ${this.repo} #${folder.name} is older than ${howOld} days. Deleting it..."
                folder.delete()
                folder.save()
            } else {
                manager.listener.logger.println "Last build for ${folder_main_build.getFullDisplayName()} is not old enough. Continuing..."
            }
         }
    }

    def triggerPullRequestJobs(manager) {
        if ( ! this.trigger_new_prs ) {
            manager.listener.logger.println "Not triggering builds for new pull-requests because it's disabled."
            return
        }
        def new_prs_file = manager.build.getWorkspace().child('new-prs.txt')
        if ( new_prs_file.exists() == false ) {
            manager.listener.logger.println "The 'new-prs.txt' file was not found in ${manager.build.getWorkspace().toString()}. Not triggering PR jobs."
            return
        }
        def triggered = []
        def slurper = new JsonSlurper()
        def new_prs = slurper.parseText(new_prs_file.readToString())
        new_prs.each { pr_id, commit_sha ->
            if ( triggered.contains(pr_id) == false) {
                try {
                    def pr_job = Jenkins.instance.getJob(this.name).getJob('pr').getJob(pr_id).getJob('main-build')
                    manager.listener.logger.println "Triggering build for ${pr_job.getFullDisplayName()} @ ${commit_sha})"
                    try {
                        this.getAuthenticatedRepository().createCommitStatus(
                            commit_sha,
                            GHCommitState.SUCCESS,
                            pr_job.getAbsoluteUrl(),
                            "Create Jobs For PR #${pr_id}",
                            'ci/create-jobs'
                        )
                    } catch(create_commit_error) {
                        manager.listener.logger.println "Failed to set commit status: " + create_commit_error.toString()
                    }
                    def trigger = pr_job.triggers.iterator().next().value
                    def ghprb_repo = trigger.getRepository()
                    def pull_req = ghprb_repo.getPullRequest(pr_id.toInteger())
                    ghprb_repo.check(pull_req)
                    pull_req = ghprb_repo.pulls.get(pr_id.toInteger())
                    ghprb_repo.helper.builds.build(pull_req, pull_req.author, 'Job Created. Start Initial Build')
                } catch(e) {
                    manager.listener.logger.println "Failed to get Job ${this.name}/pr/${pr_id}/main-build: ${e.toString()}"
                }
                triggered.add(pr_id)
            }
        }
    }

    def addBuildSummary(manager) {
        if ( manager.build.class.canonicalName == 'com.cloudbees.plugins.flow.FlowRun' ) {
            def summary = manager.createSummary('/plugin/buildgraph-view/images/48x48/chain.png')
            summary.appendText("<p>Triggered Builds</p>", false)
            summary.appendText("<dl>", false)
            manager.build.getJobsGraph().vertexSet().each {
                if ( it.getProject().getAbsoluteUrl() != manager.build.getProject().getAbsoluteUrl() ) {
                    summary.appendText("""
                        <dt style="font-weight: normal;">
                          <img src='${hudson.Functions.getResourcePath()}/images/32x32/${it.getBuild().getIconColor()}.png'/>
                          <a href='${it.getProject().getAbsoluteUrl()}'>${it.getProject().getDisplayName()}</a>
                        </dt>
                        """.stripIndent().trim(), false)
                }
            }
            summary.appendText("</dl>", false)
        }
    }

}
