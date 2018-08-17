/*
 * Copyright (C) 2018 Google Inc.
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

package com.google.copybara.hg;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import com.google.common.flogger.FluentLogger;
import com.google.copybara.ChangeMessage;
import com.google.copybara.Destination;
import com.google.copybara.DestinationEffect;
import com.google.copybara.GeneralOptions;
import com.google.copybara.LabelFinder;
import com.google.copybara.TransformResult;
import com.google.copybara.exception.CannotResolveRevisionException;
import com.google.copybara.exception.RepoException;
import com.google.copybara.exception.ValidationException;
import com.google.copybara.profiler.Profiler.ProfilerTask;
import com.google.copybara.util.DiffUtil;
import com.google.copybara.util.DiffUtil.DiffFile;
import com.google.copybara.util.DiffUtil.DiffFile.Operation;
import com.google.copybara.util.FileUtil;
import com.google.copybara.util.Glob;
import com.google.copybara.util.InsideGitDirException;
import com.google.copybara.util.console.Console;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.format.DateTimeFormatter;
import javax.annotation.Nullable;

/**
 * A Mercurial (Hg) repository destination.
 */
public class HgDestination implements Destination<HgRevision> {
  private static final String ORIGIN_LABEL_SEPARATOR = ": ";
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  static class MessageInfo {
    final ImmutableList<LabelFinder> labelsToAdd;

    MessageInfo(ImmutableList<LabelFinder> labelsToAdd) {
      this.labelsToAdd = checkNotNull(labelsToAdd);
    }
  }

  private final String repoUrl;
  private final String fetch;
  private final String push;
  private final GeneralOptions generalOptions;
  private final HgOptions hgOptions;

  HgDestination(String repoUrl, String fetch, String push, GeneralOptions generalOptions,
      HgOptions hgOptions) {
    this.repoUrl = repoUrl;
    this.fetch = fetch;
    this.push = push;
    this.generalOptions = generalOptions;
    this.hgOptions = hgOptions;
  }

  @Override
  public Writer<HgRevision> newWriter (Glob destinationFiles, boolean dryRun,
      @Nullable String groupId, @Nullable Writer<HgRevision> oldWriter) {
    return new WriterImpl(repoUrl, fetch, push, generalOptions, hgOptions);
  }

  @Override
  public String getLabelNameWhenOrigin() {
    return HgRepository.HG_ORIGIN_REV_ID;
  }

  static class WriterImpl implements Writer<HgRevision> {
    private final String repoUrl;
    private final String remoteFetch;
    private final String remotePush;
    private final GeneralOptions generalOptions;
    private final HgOptions hgOptions;
    private final boolean force;

    WriterImpl(String repoUrl, String remoteFetch, String remotePush,
        GeneralOptions generalOptions, HgOptions hgOptions) {
      this.repoUrl = checkNotNull(repoUrl);
      this.remoteFetch = checkNotNull(remoteFetch);
      this.remotePush = checkNotNull(remotePush);
      this.generalOptions = generalOptions;
      this.hgOptions = hgOptions;
      this.force = generalOptions.isForced();
    }

    @Nullable
    @Override
    public DestinationStatus getDestinationStatus(String labelName)
        throws RepoException, ValidationException {
      throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public boolean supportsHistory() {
      throw new UnsupportedOperationException("Not implemented yet");
    }

    private HgRepository getRepository() throws RepoException {
      return hgOptions.cachedBareRepoForUrl(repoUrl);
    }

    /**
     * Returns the message for a change with any labels, if set
     */
    public static ChangeMessage getChangeMessage(
        TransformResult transformResult, String originLabelSeparator) {
      MessageInfo messageInfo = new MessageInfo(transformResult.isSetRevId()
          ? ImmutableList.of(new LabelFinder(
              transformResult.getCurrentRevision().getLabelName() + originLabelSeparator
              + transformResult.getCurrentRevision().asString()))
          : ImmutableList.of());
      ChangeMessage msg = ChangeMessage.parseMessage(transformResult.getSummary());
      for (LabelFinder label : messageInfo.labelsToAdd) {
        msg = msg.withNewOrReplacedLabel(label.getName(), label.getSeparator(), label.getValue());
      }
      return msg;
    }

    private void pullFromRemote(Console console, HgRepository repo, String repoUrl,
        String reference) throws RepoException, ValidationException {
      try (ProfilerTask ignore =
          generalOptions.profiler().start("hg_destination_pull")) {
        console.progressFmt("Hg Destination: Pulling: %s from %s", reference, repoUrl);
        repo.pullFromRef(repoUrl, reference);
      } catch (CannotResolveRevisionException e) {
        String warning = String.format("Hg Destination: '%s' doesn't exist in '%s'",
            reference, repoUrl);
        if (!force) {
          throw new ValidationException(
              "%s. Use %s flag if you want to push anyway", warning, GeneralOptions.FORCE);
        }
        console.warn(warning);
      }
    }

    /**
     * Add and delete files from a repository, based on the computed diff between the repository and
     * a {@param workdir}.
     *
     * <p> This is required to write files from the {@param workdir} to the destination because
     * there is no built-in option to set the working directory of a repository. Thus, we need to
     * manually add changes to a {@param localRepo}, changing its working directory directly so it
     * contains exactly the same files as that of the {@param workdir}. Changes are staged to be
     * pushed to a remote repository.
     */
    private void getDiffAndStageChanges(Path workDir, HgRepository localRepo)
        throws RepoException, IOException {
      // Create a temp archive of the remote repository to compute diff with
      Path tempArchivePath = Files.createTempDirectory(workDir.getParent(), "tempArchive");
      localRepo.archive(tempArchivePath.toString());

      try {
        // Compute the diff between an archive of the remote repo and the workdir
        ImmutableList<DiffFile> diffFiles = DiffUtil
            .diffFiles(tempArchivePath, workDir, generalOptions.isVerbose(),
                generalOptions.getEnvironment());

        for (DiffFile diff : diffFiles) {
          Operation diffOp = diff.getOperation();

          if (diffOp.equals(Operation.ADD)) {
            Files.copy(workDir.resolve(diff.getName()),
                localRepo.getHgDir().resolve(diff.getName()), StandardCopyOption.COPY_ATTRIBUTES);
            localRepo.hg(localRepo.getHgDir(), "add", diff.getName());
          }

          if (diffOp.equals(Operation.MODIFIED)) {
            Files.copy(workDir.resolve(diff.getName()),
                localRepo.getHgDir().resolve(diff.getName()), StandardCopyOption.REPLACE_EXISTING);
          }

          if (diffOp.equals(Operation.DELETE)) {
            try {
              localRepo.hg(localRepo.getHgDir(), "remove", diff.getName());
            } catch (RepoException e) {
              // Ignore a .hg_archival file that is not in the workdir nor in the local repo.
              if (!e.getMessage().contains(".hg_archival.txt: No such file or directory")) {
                throw e;
              }
            }
          }
        }
      } catch (InsideGitDirException e) {
        throw new RepoException(String.format("Error computing file diff: %s", e.getMessage()), e);
      } finally {
        FileUtil.deleteRecursively(tempArchivePath);
      }
    }

    /**
     * Writes the changes in {@param transformResult} to the destination repository.
     */
    @Override
    public ImmutableList<DestinationEffect> write(TransformResult transformResult, Console console)
        throws ValidationException, RepoException, IOException {
      Path workdir = transformResult.getPath();
      logger.atInfo().log("Exporting from %s to: %s", workdir, this);

      HgRepository localRepo = getRepository();
      console.progress("Hg Destination: Pulling from " + remoteFetch);
      pullFromRemote(console, localRepo, repoUrl, remoteFetch);
      localRepo.cleanUpdate(remoteFetch);

      // Set the default path of the local repo to be the remote repo, so we can push to it
      Files.write(localRepo.getHgDir().resolve(".hg/hgrc"),
          String.format("[paths]\ndefault = %s\n", repoUrl).getBytes(StandardCharsets.UTF_8));

      //TODO(jlliu): include/exclude destinationFiles

      console.progress("Hg Destination: Computing diff");
      getDiffAndStageChanges(workdir, localRepo);

      console.progress("Hg Destination: Creating a local commit");

      ChangeMessage msg = getChangeMessage(transformResult, ORIGIN_LABEL_SEPARATOR);
      String date = transformResult.getTimestamp().format(DateTimeFormatter.RFC_1123_DATE_TIME);

      localRepo.hg(localRepo.getHgDir(), "commit", "--user",
          transformResult.getAuthor().toString(), "--date", date,
          "-m", msg.toString());

      console.progress(String.format("Hg Destination: Pushing to %s %s", repoUrl, remotePush));
      localRepo.hg(localRepo.getHgDir(), "push", "--rev", remotePush, repoUrl);

      String tip = localRepo.identify("tip").getGlobalId();

      return ImmutableList.of(
          new DestinationEffect(
              DestinationEffect.Type.CREATED,
              String.format("Created revision %s", tip),
              transformResult.getChanges().getCurrent(),
              new DestinationEffect.DestinationRef(
                  tip, "commit", repoUrl)));
    }

    @Override
    public void visitChanges(@Nullable HgRevision start, ChangesVisitor visitor)
      throws RepoException, ValidationException {
      throw new UnsupportedOperationException("Not implemented yet");
    }
  }

  /**
   * Builds a new {@link HgDestination}
   */
  static HgDestination newHgDestination(String url, String fetch, String push,
      GeneralOptions generalOptions, HgOptions hgOptions) {
    return new HgDestination(url, fetch, push, generalOptions, hgOptions);
  }
}
