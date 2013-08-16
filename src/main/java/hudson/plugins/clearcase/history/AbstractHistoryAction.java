/**
 * The MIT License
 *
 * Copyright (c) 2007-2010, Sun Microsystems, Inc., Kohsuke Kawaguchi, Erik Ramfelt,
 *                          Henrik Lynggaard, Peter Liljenberg, Andrew Bayer, Vincent Latombe
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.plugins.clearcase.history;

import static hudson.plugins.clearcase.util.OutputFormat.COMMENT;
import static hudson.plugins.clearcase.util.OutputFormat.LINEEND;
import hudson.FilePath;
import hudson.plugins.clearcase.AbstractClearCaseScm.ChangeSetLevel;
import hudson.plugins.clearcase.ClearTool;
import hudson.plugins.clearcase.util.ClearToolFormatHandler;
import hudson.scm.ChangeLogSet.Entry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.text.MessageFormat;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;

import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.Validate;

/**
 * @author hlyh
 */
public abstract class AbstractHistoryAction implements HistoryAction {

    private static final Logger LOG = Logger.getLogger(AbstractHistoryAction.class.getName());

    protected ClearTool         cleartool;
    protected String            extendedViewPath;
    protected boolean           isDynamicView;
    boolean                     useRecurse;
    private ChangeSetLevel      changeset;
    private Filter              filter;
    private FilePath            updtFile;

    public AbstractHistoryAction(ClearTool cleartool, boolean isDynamicView, Filter filter, ChangeSetLevel changeset, boolean useRecurse) {
        this.cleartool = cleartool;
        this.filter = filter;
        this.isDynamicView = isDynamicView;
        this.changeset = changeset;
        this.useRecurse = useRecurse;
    }

    @Override
    public List<Entry> getChanges(Date time, String viewPath, String viewTag, String[] branchNames, String[] viewPaths) throws IOException,
    InterruptedException {
        List<Entry> changelog;
        if (needsLsHistoryForGetChanges(viewTag, viewPaths)) {
            List<HistoryEntry> historyEntries = runAndFilterLsHistory(time, viewPath, viewTag, branchNames, viewPaths);
            changelog = (List<Entry>) buildChangelog(viewPath, historyEntries);
        } else {
            changelog = Collections.emptyList();
        }
        return changelog;
    }

    public ChangeSetLevel getChangeset() {
        return changeset;
    }

    public String getExtendedViewPath() {
        return extendedViewPath;
    }

    public String getLsHistoryFormat() {
        return MessageFormat.format("{0}{1}{2}", getHistoryFormatHandler().getFormat(), COMMENT, LINEEND);
    }

    public FilePath getUpdtFile() {
        return updtFile;
    }

    @Override
    public boolean hasChanges(Date time, String viewPath, String viewTag, String[] branchNames, String[] viewPaths) throws IOException, InterruptedException {
        if (needsLsHistoryForHasChanges(viewTag, viewPaths)) {
            List<HistoryEntry> historyEntries = runAndFilterLsHistory(time, viewPath, viewTag, branchNames, viewPaths);
            return historyEntries.size() > 0;
        }
        return false;
    }

    /**
     * Sets the extended view path. The extended view path will be removed from file paths in the event. The extended view path is for example the view root +
     * view name; and this path shows up in the history and can be confusing for users.
     * 
     * @param path
     *            the new extended view path.
     */
    public void setExtendedViewPath(String path) {
        this.extendedViewPath = path;
    }

    @Override
    public void setUpdtFile(FilePath updtFile) {
        this.updtFile = updtFile;
    }

    protected abstract List<? extends Entry> buildChangelog(String viewPath, List<HistoryEntry> entries) throws IOException, InterruptedException;

    protected List<HistoryEntry> filterEntries(List<HistoryEntry> entries) throws IOException, InterruptedException {
        if (filter == null) {
            LOG.fine("no filter");
            return entries;
        }
        List<HistoryEntry> filtered = new ArrayList<HistoryEntry>();
        for (HistoryEntry entry : entries) {
            boolean accepted = filter.accept(entry);
            LOG.log(Level.FINE, "filter={0} entry={1} accepted={2}", new Object[] { filter, entry, accepted });
            if (accepted) {
                filtered.add(entry);
            }
        }
        return filtered;
    }

    protected abstract ClearToolFormatHandler getHistoryFormatHandler();

    protected boolean needsLsHistoryForGetChanges(String viewTag, String[] loadRules) throws IOException, InterruptedException {
        // check if right changeset level is enabled (all changesets except NONE needs LsHistory call)
        // it is also true if runLsHistory will call another method, e.g. not lshistory, but parse update files
        // or call other cleartool method to get the history!
        return !ChangeSetLevel.NONE.equals(changeset) &&
                // if view not exist we should not execute history!
                cleartool.doesViewExist(viewTag) &&
                // if load rules are empty we should not execute history
                !ArrayUtils.isEmpty(loadRules);
    }

    protected abstract HistoryEntry parseEventLine(Matcher matcher, String line) throws IOException, InterruptedException, ParseException;

    protected void parseLsHistory(BufferedReader reader, Collection<HistoryEntry> history) throws IOException, InterruptedException, ParseException {
        HistoryEntry previousEntry = null;

        for (String line = reader.readLine(); line != null; line = reader.readLine()) {
            if (!gotACleartoolError(line)) {
                Matcher matcher = getHistoryFormatHandler().checkLine(line);
                if (startOfLsHistoryEntry(matcher)) {
                    previousEntry = buildHistoryEntry(history, line, matcher);
                } else {
                    tryToAttachLineToPreviousEntry(previousEntry, line);
                }
            } else {
                processError(line);
            }
        }
    }

    protected List<HistoryEntry> runLsHistory(Date time, String viewPath, String viewTag, String[] branchNames, String[] viewPaths) throws IOException,
    InterruptedException {
        Validate.notNull(viewPath);
        List<HistoryEntry> historyEntries;

        prepareViewForHistory(viewTag);
        try {
            historyEntries = retrieveHistoryEntries(time, viewPath, branchNames, viewPaths);
        } catch (ParseException ex) {
            historyEntries = Collections.emptyList();
        }
        return historyEntries;
    }

    private HistoryEntry buildHistoryEntry(Collection<HistoryEntry> history, String line, Matcher matcher) throws IOException, InterruptedException,
    ParseException {
        HistoryEntry currentEntry;
        currentEntry = parseEventLine(matcher, line).normalize(extendedViewPath);
        history.add(currentEntry);
        return currentEntry;
    }

    private BufferedReader getLsHistoryBufferedReader(Date time, String viewPath, String[] viewPaths, String branchName) throws IOException,
    InterruptedException {
        return new BufferedReader(getLsHistoryReader(time, viewPath, viewPaths, branchName));
    }

    private Reader getLsHistoryReader(Date time, String viewPath, String[] viewPaths, String branchName) throws IOException, InterruptedException {
        return cleartool.lshistory(getLsHistoryFormat(), time, viewPath, branchName, viewPaths, needMinorEvents(), useRecurse);
    }

    private boolean gotACleartoolError(String line) {
        return line.startsWith("cleartool: Error:");
    }

    private boolean needMinorEvents() {
        return (filter != null) && (filter.requiresMinorEvents());
    }

    private boolean needsLsHistoryForHasChanges(String viewTag, String[] loadRules) throws IOException, InterruptedException {
        // if view not exist we should not execute history!
        return cleartool.doesViewExist(viewTag) &&
                // if load rules are empty we should not execute history
                !ArrayUtils.isEmpty(loadRules);
    }

    private String[] normalizeBranches(String[] branchNames) {
        if (ArrayUtils.isEmpty(branchNames)) {
            // If no branch was specified lshistory should be called
            // without branch filtering.
            // This solves [HUDSON-4800] and is required for [HUDSON-7218].
            branchNames = new String[] { StringUtils.EMPTY };
        }
        return branchNames;
    }

    private void prepareViewForHistory(String viewTag) throws IOException, InterruptedException {
        if (isDynamicView) {
            cleartool.startView(viewTag);
        }
    }

    private void processError(String line) {
        // TODO: better error handling
    }

    private List<HistoryEntry> retrieveHistoryEntries(Date time, String viewPath, String[] branchNames, String[] viewPaths) throws IOException,
    InterruptedException, ParseException {
        List<HistoryEntry> historyEntries = new ArrayList<HistoryEntry>();
        for (String branchName : normalizeBranches(branchNames)) {
            BufferedReader bufferedReader = getLsHistoryBufferedReader(time, viewPath, viewPaths, branchName);
            parseLsHistory(bufferedReader, historyEntries);
            bufferedReader.close();
        }
        return historyEntries;
    }

    private List<HistoryEntry> runAndFilterLsHistory(Date time, String viewPath, String viewTag, String[] branchNames, String[] viewPaths) throws IOException,
    InterruptedException {
        List<HistoryEntry> historyEntries = runLsHistory(time, viewPath, viewTag, branchNames, viewPaths);
        List<HistoryEntry> filtered = filterEntries(historyEntries);
        LOG.log(Level.FINE, "@{0} historyEntries={1} -> {2}", new Object[] { time, historyEntries, filtered });
        return filtered;
    }

    private boolean startOfLsHistoryEntry(Matcher matcher) {
        return matcher != null;
    }

    private void tryToAttachLineToPreviousEntry(HistoryEntry previousEntry, String line) {
        if (previousEntry != null) {
            previousEntry.appendComment(line).appendComment("\n");
        } else {
            LOG.warning("Got the comment : \"" + line + "\" but couldn't attach it to any entry");
        }
    }
}
