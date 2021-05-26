/* dCache - http://www.dcache.org/
 *
 * Copyright (C) 2021 - 2022 Deutsches Elektronen-Synchrotron
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.dcache.trs;

import diskCacheV111.util.PnfsId;

import java.util.OptionalLong;

/**
 * Bring online job scheduling item for tracking a job's meta-information relevant for job
 * scheduling
 */
public class TrsJob {

    private final String jobid;
    private final String pnfsid;
    private final long creationTime;
    private OptionalLong fileSize = OptionalLong.empty();
    private boolean attemptedToRetrieveTapeLocationInfo = false;

    public TrsJob(String jobid, String pnfsid) {
        this(jobid, pnfsid, System.currentTimeMillis());
    }

    public TrsJob(String jobid, String pnfsId, long ctime) {
        this.jobid = jobid;
        this.pnfsid = pnfsId;
        this.creationTime = ctime;
    }

    public String getJobid() {
        return jobid;
    }

    public String getIdentifier() {
        return jobid + ":" + pnfsid;
    }

    public String getPnfsidString() {
        return pnfsid;
    }

    public PnfsId getPnfsid() {
        return new PnfsId(pnfsid);
    }

    public long getCreationTime() {
        return creationTime;
    }

    public OptionalLong getFileSize() {
        return fileSize;
    }

    public void setFileSize(long fileSize) {
        this.fileSize = OptionalLong.of(fileSize);
    }

    public boolean attemptedToRetrieveTapeLocationInfo() {
        return attemptedToRetrieveTapeLocationInfo;
    }

    public void setAttemptedToRetrieveTapeLocationInfo() {
        attemptedToRetrieveTapeLocationInfo = true;
    }

    public String toString() {
        return "jid: " + jobid + ", pnfsid: " + pnfsid + ", ctime: " + creationTime;
    }

}
