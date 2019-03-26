package de.refactoringbot.api.gitlab;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.refactoringbot.configuration.BotConfiguration;
import de.refactoringbot.model.configuration.GitConfiguration;
import de.refactoringbot.model.exceptions.BotRefactoringException;
import de.refactoringbot.model.exceptions.GitHubAPIException;
import de.refactoringbot.model.exceptions.GitLabAPIException;
import de.refactoringbot.model.exceptions.ValidationException;
import de.refactoringbot.model.gitlab.pullrequest.GitLabCreateRequest;
import de.refactoringbot.model.gitlab.pullrequest.GitLabPullRequest;
import de.refactoringbot.model.gitlab.pullrequest.GitLabPullRequests;
import de.refactoringbot.model.gitlab.pullrequestcomment.GitLabPullRequestComment;
import de.refactoringbot.model.gitlab.pullrequestcomment.GitLabPullRequestComments;
import de.refactoringbot.model.gitlab.repository.GitLabRepository;
import de.refactoringbot.model.gitlab.user.GitLabUser;

/**
 * This class communicates with the Gitlab-API.
 * 
 * @author Stefan Basaric
 *
 */
@Component
public class GitlabDataGrabber {

	@Autowired
	ObjectMapper mapper;
	@Autowired
	BotConfiguration botConfig;

	private static final Logger logger = LoggerFactory.getLogger(GitlabDataGrabber.class);

	private static final String USER_AGENT = "Mozilla/5.0";
	private static final String GITLAB_SCHEME = "https";
	private static final String GITLAB_HOST = "gitlab.com";
	private static final String GITLAB_APIPATH = "api/v4/";
	private static final String TOKEN_HEADER = "Private-Token";

	/**
	 * This method tries to get a repository from github.
	 * 
	 * @param repoName
	 * @param repoOwner
	 * @param botToken
	 * @throws MalformedURLException
	 * @throws GitHubAPIException
	 */
	public void checkRepository(String repoName, String repoOwner, String botToken)
			throws GitLabAPIException, MalformedURLException {
		// Build URI
		URI gitlabURI = URI.create("https://gitlab.com/api/v4/projects/" + repoOwner + "%2F" + repoName);

		RestTemplate rest = new RestTemplate();

		HttpHeaders headers = new HttpHeaders();
		headers.set("User-Agent", USER_AGENT);
		headers.set(TOKEN_HEADER, botToken);
		HttpEntity<String> entity = new HttpEntity<>("parameters", headers);

		try {
			// Send request to the GitLab-API
			rest.exchange(gitlabURI, HttpMethod.GET, entity, GitLabRepository.class).getBody();
		} catch (RestClientException e) {
			logger.error(e.getMessage(), e);
			throw new GitLabAPIException("Repository does not exist on Github or invalid Bot-Token!", e);
		}
	}

	/**
	 * This method tries to get a user with the given token.
	 * 
	 * @param botUsername
	 * @param botToken
	 * @param botEmail
	 * @return
	 * @throws Exception
	 */
	public void checkGithubUser(String botUsername, String botToken, String botEmail) throws Exception {
		// Build URI
		UriComponentsBuilder apiUriBuilder = UriComponentsBuilder.newInstance().scheme(GITLAB_SCHEME).host(GITLAB_HOST)
				.path(GITLAB_APIPATH + "user");

		URI gitlabURI = apiUriBuilder.build().encode().toUri();

		RestTemplate rest = new RestTemplate();
		HttpHeaders headers = new HttpHeaders();
		headers.set("User-Agent", USER_AGENT);
		headers.set(TOKEN_HEADER, botToken);
		HttpEntity<String> entity = new HttpEntity<>("parameters", headers);

		GitLabUser gitLabUser = null;
		try {
			// Send request to the GitLab-API
			gitLabUser = rest.exchange(gitlabURI, HttpMethod.GET, entity, GitLabUser.class).getBody();
		} catch (RestClientException e) {
			logger.error(e.getMessage(), e);
			throw new GitLabAPIException("Invalid Bot-Token!");
		}

		// Check if user exists and has a public email
		if (!botUsername.equals(gitLabUser.getUsername())) {
			throw new ValidationException("Bot-User does not exist on GitLab!");
		}
		if (gitLabUser.getPublicEmail() == null) {
			throw new ValidationException("Bot-User does not have a public email on GitLab!");
		}
		if (!gitLabUser.getPublicEmail().equals(botEmail)) {
			throw new ValidationException("Invalid Bot-Email!");
		}
	}
	
	/**
	 * This method checks if a branch with a specific name exists on the fork. If
	 * such a branch exists, the method throws an exception.
	 * 
	 * @param gitConfig
	 * @param branchName
	 * @throws URISyntaxException
	 * @throws BotRefactoringException
	 * @throws GitLabAPIException
	 */
	public void checkBranch(GitConfiguration gitConfig, String branchName)
			throws URISyntaxException, BotRefactoringException, GitLabAPIException {
        // Build URI
		URI uri = createURIFromApiLink(gitConfig.getForkApiLink() + "/repository/branches/" + branchName);

		RestTemplate rest = new RestTemplate();
		HttpHeaders headers = new HttpHeaders();
		headers.set("User-Agent", USER_AGENT);
		headers.set(TOKEN_HEADER, gitConfig.getBotToken());
		HttpEntity<String> entity = new HttpEntity<>("parameters", headers);
		
		try {
			// Send Request to the GitLab-API
			rest.exchange(uri, HttpMethod.GET, entity, String.class).getBody();
			throw new BotRefactoringException(
					"Issue was already refactored in the past! The bot database might have been resetted but not the fork itself.");
		} catch (RestClientException e) {
			// If branch does not exist -> return
			if (e.getMessage().equals("404 Not Found")) {
				return;
			}
			logger.error(e.getMessage(), e);
			throw new GitLabAPIException("Could not get Branch from GitLab!", e);
		}
	}

	/**
	 * This method creates a fork from a repository.
	 * 
	 * @param gitConfig
	 * @return
	 * @throws URISyntaxException
	 * @throws GitHubAPIException
	 */
	public void createFork(GitConfiguration gitConfig) throws URISyntaxException, GitLabAPIException {

		// Build URI
		URI forkUri = createURIFromApiLink(gitConfig.getRepoApiLink() + "/fork");

		RestTemplate rest = new RestTemplate();
		HttpHeaders headers = new HttpHeaders();
		headers.set("User-Agent", USER_AGENT);
		headers.set(TOKEN_HEADER, gitConfig.getBotToken());
		HttpEntity<String> entity = new HttpEntity<>("parameters", headers);

		try {
			// Send request to the GitLab-API
			rest.exchange(forkUri, HttpMethod.POST, entity, GitLabRepository.class).getBody();
		} catch (RestClientException r) {
			throw new GitLabAPIException("Could not create fork on GitLab!", r);
		}
	}

	/**
	 * This method deletes a repository from Github.
	 * 
	 * @param gitConfig
	 * @throws URISyntaxException
	 * @throws GitLabAPIException
	 */
	public void deleteRepository(GitConfiguration gitConfig) throws URISyntaxException, GitLabAPIException {
		String originalRepo = gitConfig.getRepoApiLink();
		String forkRepo = gitConfig.getForkApiLink();
		// Never delete the original repository
		if (originalRepo.equals(forkRepo)) {
			return;
		}

		// Read URI from configuration
		URI repoUri = createURIFromApiLink(gitConfig.getForkApiLink());

		RestTemplate rest = new RestTemplate();
		HttpHeaders headers = new HttpHeaders();
		headers.set("User-Agent", USER_AGENT);
		headers.set(TOKEN_HEADER, gitConfig.getBotToken());
		HttpEntity<String> entity = new HttpEntity<>("parameters", headers);

		try {
			// Send request to the GitLab-API
			rest.exchange(repoUri, HttpMethod.DELETE, entity, String.class);
		} catch (RestClientException r) {
			throw new GitLabAPIException("Could not delete repository from GitLab!", r);
		}
	}

	/**
	 * This method returns all PullRequest from Github.
	 * 
	 * @return allRequests
	 * @throws URISyntaxException
	 * @throws GitLabAPIException
	 * @throws IOException
	 */
	public GitLabPullRequests getAllPullRequests(GitConfiguration gitConfig)
			throws URISyntaxException, GitLabAPIException, IOException {
		// Build URI
		URI requestUri = createURIFromApiLink(gitConfig.getRepoApiLink() + "/merge_requests?state=opened");

		RestTemplate rest = new RestTemplate();
		HttpHeaders headers = new HttpHeaders();
		headers.set("User-Agent", USER_AGENT);
		headers.set(TOKEN_HEADER, gitConfig.getBotToken());
		HttpEntity<String> entity = new HttpEntity<>("parameters", headers);

		String json = null;
		try {
			// Send Request to the GitLab-API
			json = rest.exchange(requestUri, HttpMethod.GET, entity, String.class).getBody();
		} catch (RestClientException e) {
			logger.error(e.getMessage(), e);
			throw new GitLabAPIException("Could not get Pull-Requests from GitLab!", e);
		}

		GitLabPullRequests allRequests = new GitLabPullRequests();

		try {
			List<GitLabPullRequest> requestList = mapper.readValue(json,
					mapper.getTypeFactory().constructCollectionType(List.class, GitLabPullRequest.class));
			allRequests.setAllPullRequests(requestList);
			return allRequests;
		} catch (IOException e) {
			logger.error(e.getMessage(), e);
			throw new IOException("Could not create object from GitLab-Request json!", e);
		}
	}

	/**
	 * This method returns all comments of a specific pull request from GitLab.
	 * 
	 * @param commentUri
	 * @param gitConfig
	 * @return allComments
	 * @throws GitHubAPIException
	 * @throws IOException
	 */
	public GitLabPullRequestComments getAllPullRequestComments(URI commentUri, GitConfiguration gitConfig) throws GitLabAPIException, IOException {
		RestTemplate rest = new RestTemplate();
		HttpHeaders headers = new HttpHeaders();
		headers.set("User-Agent", USER_AGENT);
		headers.set(TOKEN_HEADER, gitConfig.getBotToken());
		HttpEntity<String> entity = new HttpEntity<>("parameters", headers);

		String json = null;
		try {
			// Send request to the GitLab-API
			json = rest.exchange(commentUri, HttpMethod.GET, entity, String.class).getBody();
		} catch (RestClientException r) {
			throw new GitLabAPIException("Could not get pull request comments from GitLab!", r);
		}

		GitLabPullRequestComments allComments = new GitLabPullRequestComments();

		try {
			// Try to map json to object
			List<GitLabPullRequestComment> commentList = mapper.readValue(json,
					mapper.getTypeFactory().constructCollectionType(List.class, GitLabPullRequestComment.class));
			allComments.setComments(commentList);
			return allComments;
		} catch (IOException e) {
			logger.error(e.getMessage(), e);
			throw new IOException("Could not create object from GitLab-Comment json!", e);
		}
	}
	
	/**
	 * This method creates a pull request on GitLab.
	 * 
	 * @param createRequest
	 * @param gitConfig
	 * @return newPullRequest
	 * @throws URISyntaxException
	 * @throws GitLabAPIException
	 */
	public GitLabPullRequest createRequest(GitLabCreateRequest createRequest,
			GitConfiguration gitConfig) throws URISyntaxException, GitLabAPIException{
		// Build URI
		URI uri = createURIFromApiLink(gitConfig.getRepoApiLink() + "/merge_requests");
		
		RestTemplate rest = new RestTemplate();
//		MultiValueMap<String, String> headers = new LinkedMultiValueMap<String, String>();
//		headers.add("User-Agent", USER_AGENT);
//		headers.add(TOKEN_HEADER, gitConfig.getBotToken());
//		HttpEntity<GitLabCreateRequest> entity = new HttpEntity<>(createRequest, headers);
		HttpHeaders headers = new HttpHeaders();
		headers.set("User-Agent", USER_AGENT);
		headers.set(TOKEN_HEADER, gitConfig.getBotToken());
		HttpEntity<String> entity = new HttpEntity<>("parameters", headers);
		
		try {
			// Send request to the GitLab-API
			return rest.exchange(uri, HttpMethod.POST, entity, GitLabPullRequest.class)
					.getBody();
		} catch (RestClientException r) {
			throw new GitLabAPIException("Could not create pull request on GitLab!", r);
		}
	}

	/**
	 * Attempts to instantiate a URI object using the specified API link
	 * 
	 * @param link
	 * @return uri
	 * @throws URISyntaxException
	 */
	private URI createURIFromApiLink(String link) throws URISyntaxException {
		URI result = null;
		try {
			result = new URI(link);
		} catch (URISyntaxException u) {
			logger.error(u.getMessage(), u);
			throw new URISyntaxException("Could not create URI from given API link!", u.getMessage());
		}
		return result;
	}

}
