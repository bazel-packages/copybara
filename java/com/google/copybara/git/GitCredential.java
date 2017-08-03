/*
 * Copyright (C) 2016 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.copybara.git;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.regex.Pattern.compile;

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.Maps;
import com.google.copybara.RepoException;
import com.google.copybara.ValidationException;
import com.google.devtools.build.lib.shell.BadExitStatusException;
import com.google.devtools.build.lib.shell.Command;
import com.google.devtools.build.lib.shell.CommandException;
import com.google.devtools.build.lib.shell.CommandResult;
import com.google.devtools.build.lib.shell.TimeoutKillableObserver;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * Utility class for executing 'git credential' commands.
 */
public final class GitCredential {

  private static final java.util.regex.Pattern NEW_LINE = compile("\\r\\n|\\n|\\r");

  private final String gitBinary;
  private final Duration timeout;
  private final Map<String, String> env;

  GitCredential(String gitBinary, Duration timeout, Map<String, String> env) {
    this.gitBinary = Preconditions.checkNotNull(gitBinary);
    this.timeout = Preconditions.checkNotNull(timeout);
    this.env = Preconditions.checkNotNull(env);
  }

  /**
   * Execute 'git credential fill' for a url
   *
   * @param cwd the directory to execute the command. This is important if credential configuration
   * is set in the local git config.
   * @param url url to get the credentials from
   * @return a username and password
   * @throws RepoException If the url doesn't have protocol (For example https://), the url is
   * not valid or username/password couldn't be found
   */
  public UserPassword fill(Path cwd, String url)
      throws RepoException, ValidationException {
    Map<String, String> env = Maps.newHashMap(this.env);

    // Prevent from asking user for the password (It goes directly to the terminal, not to the
    // passed InputStream/OutputStream.
    env.put("GIT_ASKPASS", "");
    URI uri;

    try {
      uri = URI.create(url);
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Cannot get credentials for " + url, e);
    }
    String protocol = uri.getScheme();
    if (Strings.isNullOrEmpty(protocol)) {
      throw new ValidationException("Cannot find the protocol for " + url);
    }
    String host = uri.getHost();
    Command cmd = new Command(new String[]{gitBinary, "credential", "fill"}, env,
        cwd.toFile());
    String request = String.format("protocol=%s\nhost=%s\n", protocol, host);
    if (!Strings.isNullOrEmpty(uri.getPath())) {
      request += String.format("path=%s\n", uri.getPath());
    }
    request += "\n";

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    try {
      // DON'T REPLACE THIS WITH CommandUtil.executeCommand. WE DON'T WANT TO ACCIDENTALLY LOG THE
      // PASSWORD!
      CommandResult result = cmd.execute(
          new ByteArrayInputStream(request.getBytes(UTF_8)),
          new TimeoutKillableObserver(timeout.toMillis()), out,
          err);
      if (!result.getTerminationStatus().success()) {
        throw new RepoException("Error getting credentials:\n"
            + new String(err.toByteArray(), UTF_8));
      }
    } catch (BadExitStatusException e) {
      String errStr = new String(err.toByteArray(), UTF_8);
      if (errStr.contains("could not read")) {
        throw new ValidationException("Interactive prompting of passwords for git is disabled,"
            + " use git credential store before calling Copybara.");
      }
      throw new RepoException("Error getting credentials:\n" + errStr,e );
    } catch (CommandException e) {
      throw new RepoException("Error getting credentials", e);
    }

    Map<String, String> map = Splitter.on(NEW_LINE)
        .omitEmptyStrings()
        .withKeyValueSeparator("=")
        .split(new String(out.toByteArray(), UTF_8));

    if (!map.containsKey("username")) {
      throw new RepoException("git credentials for " + url + " didn't return a username");
    }
    if (!map.containsKey("password")) {
      throw new RepoException("git credentials for " + url + " didn't return a password");
    }
    return new UserPassword(map.get("username"), map.get("password"));
  }

  /**
   * A class that contains a username and password for git repositories.
   */
  public static class UserPassword {

    private final String username;
    private final String password;

    private UserPassword(String username, String password) {
      this.username = Preconditions.checkNotNull(username);
      this.password = Preconditions.checkNotNull(password);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("username", username)
          // DON'T CHANGE THIS
          .add("password", "(hidden)")
          .toString();
    }

    /** Get username */
    public String getUsername() {
      return username;
    }

    /** Get the password. BE CAREFUL AND DON'T LOG IT! */
    public String getPassword_BeCareful() {
      return password;
    }
  }
}