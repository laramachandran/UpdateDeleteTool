package com.ainq.izgateway.extract;

import static com.ainq.izgateway.extract.Args.hasArgument;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOCase;
import org.apache.commons.io.filefilter.WildcardFileFilter;

import com.ainq.izgateway.extract.validation.BeanValidator;
import com.ainq.izgateway.extract.validation.NullValidator;

public class DeleteUpdateProcessor {
    private static final int MAX_RECORDS = 200000;
    private static int addCount = 0, disposedAsAdd = 0;
    private static int updateCount = 0, disposedAsUpdate = 0;
    private static int deleteCount = 0, disposedAsDelete = 0;
    private static String headers[] = CVRSExtract.getHeaders(Validator.DEFAULT_VERSION);

    private static class CVRSIndex implements Comparable<CVRSIndex> {
        private static final int UNKNOWN = 0, UPDATE = 1, DELETE = 2, ADD = 3, OLD = 4;
        private String patientId;
        private String vaxId;
        private int fileIndex;
        private int filePos;
        private int destination = UNKNOWN;

        /**
         * @return the patientId
         */
        public String getPatientId() {
            return patientId;
        }
        /**
         * @param patientId the patientId to set
         */
        public void setPatientId(String patientId) {
            this.patientId = patientId;
        }
        /**
         * @return the vaxId
         */
        public String getVaxId() {
            return vaxId;
        }
        /**
         * @param vaxId the vaxId to set
         */
        public void setVaxId(String vaxId) {
            this.vaxId = vaxId;
        }

        /**
         * @return the fileIndex
         */
        public int getFileIndex() {
            return fileIndex;
        }
        /**
         * @param fileIndex the fileIndex to set
         */
        public void setFileIndex(int fileIndex) {
            this.fileIndex = fileIndex;
        }
        /**
         * @return the filePos
         */
        public int getFilePos() {
            return filePos;
        }
        /**
         * @param filePos the filePos to set
         */
        public void setFilePos(int filePos) {
            this.filePos = filePos;
        }

        /**
         * @return the destination
         */
        public int getDestination() {
            return destination;
        }

        /**
         * @param destination the destination to set
         */
        public void setDestination(int destination) {
            this.destination = destination;
        }

        public int compareCVRSTo(CVRSIndex x) {
            int result = this.getPatientId().compareTo(x.getPatientId());
            if (result != 0) {
                return result;
            }
            result = this.getVaxId().compareTo(x.getVaxId());
            return result;
        }

        public static int sortByIds(CVRSIndex a, CVRSIndex b) {
            return a.compareCVRSTo(b);
        }

        public static int sortByFileAndPosition(CVRSIndex a, CVRSIndex b) {
            int result = Integer.valueOf(a.getFileIndex()).compareTo(b.getFileIndex());
            if (result != 0) {
                return result;
            }
            return Integer.valueOf(a.getFilePos()).compareTo(b.getFilePos());
        }

        public int compareTo(CVRSIndex that) {
            int result = getPatientId().compareTo(that.getPatientId());
            if (result != 0) {
                return result;
            }
            result = getVaxId().compareTo(that.getVaxId());
            if (result != 0) {
                return result;
            }
            result = Integer.valueOf(getFileIndex()).compareTo(that.getFileIndex());
            if (result != 0) {
                return result;
            }
            result = Integer.valueOf(getFilePos()).compareTo(that.getFilePos());
            if (result != 0) {
                return result;
            }
            result = Integer.valueOf(getDestination()).compareTo(that.getDestination());
            return result;
        }

        public boolean equals(Object that) {
            if (that instanceof CVRSIndex) {
                return compareTo((CVRSIndex) that) == 0;
            }
            return false;
        }

        public int hashCode() {
            return getPatientId().hashCode() ^
                getVaxId().hashCode() ^
                (int) getFilePos() ^
                getFileIndex() ^ getDestination();
        }

    }
    /**
     * Process old and new files and produce a list of records to
     * delete, records to update and records to add.
     *
     * @param args  The command line arguments
     * @throws Exception
     */
    public static void main(String args[]) throws Exception {
        boolean skip = false, errors = false;
        List<File> newFiles = new ArrayList<>(), oldFiles = new ArrayList<>();
        File folder = null;
        boolean validate = false;

        for (String arg: args) {
            if (hasArgument(arg,"-k", "Start comment")) {
                skip = true;
                continue;
            }
            if (hasArgument(arg,"-K", "End comment")) {
                skip = false;
                continue;
            }
            // If skip = true, only skip options will be processed and other arguments will be
            // skipped.  This is handy for quick debugging with script files or in Eclipse.
            // Can also be used to include Komments in parameters
            if (skip) {
                continue;
            }
            if (hasArgument(arg, "-n[new file]", "New CVRS Data to Submit")) {
                getFilenames(arg.substring(2), newFiles);
                continue;
            }
            if (hasArgument(arg, "-o[old file]", "Old CVRS Files to deduplicate against")) {
                getFilenames(arg.substring(2), oldFiles);
                continue;
            }
            if (hasArgument(arg, "-f[output folder]", "Location to store Delete/Update/Add/Report files")) {
                folder = new File(arg.substring(2));
                if (!folder.exists()) {
                    folder.mkdirs();
                }
                continue;
            }

            if (hasArgument(arg, "-v", "Enable Input Validation")) {
                validate = true;
                continue;
            }

            System.err.println("Unrecognized argument: " + arg);
            errors = true;
        }

        if (oldFiles.isEmpty()) {
            System.err.println("There are no old files to process.");
            errors = true;
        }
        if (newFiles.isEmpty()) {
            System.err.println("There are no new files to process.");
            errors = true;
        }
        if (folder == null) {
            folder = new File(".");
        }
        if (!checkForSameFile(newFiles, oldFiles)) {
            errors = true;
        }
        for (File f: newFiles) {
            if (!f.exists()) {
                System.err.printf("New CVRS file %s does not exist\n", f);
                errors = true;
            }
        }
        for (File f: oldFiles) {
            if (!f.exists()) {
                System.err.printf("Old CVRS file %s does not exist\n", f);
                errors = true;
            }
        }

        if (errors) {
            System.exit(1);
        }
        processFiles(newFiles, oldFiles, folder, validate);
    }

    private static void processFiles(List<File> newFiles, List<File> oldFiles, File folder, boolean validate) throws Exception {
        List<CVRSIndex> newIndex = new ArrayList<>(),
                        oldIndex = new ArrayList<>();

        readCVRSFromFiles(newFiles, newIndex, validate);
        readCVRSFromFiles(oldFiles, oldIndex, validate);
        Collections.sort(newIndex, CVRSIndex::sortByIds);
        Collections.sort(oldIndex, CVRSIndex::sortByIds);
        System.out.printf("Records Read:\nOld: %d\nNew: %d\n", oldIndex.size(), newIndex.size());
        setDispositions(newIndex, oldIndex);
        System.out.println("Dispositions:");
        printTotals(System.out);
        Collections.sort(newIndex, CVRSIndex::sortByFileAndPosition);
        Collections.sort(oldIndex, CVRSIndex::sortByFileAndPosition);
        System.out.println("Writing Update/Add files");
        createFiles(newFiles, newIndex, folder, validate);
        printTotals(System.out);
        System.out.println("Writing Delete files");
        createFiles(oldFiles, oldIndex, folder, validate);

        System.out.println("Processing Complete");
        printTotals(System.out);

        System.out.printf("   Adds %d + Updates %d = %d New = %d\n", addCount, updateCount, addCount + updateCount, newIndex.size());
        if (addCount + updateCount != newIndex.size()) {
            throw new Exception("Counts do not match for New Files");
        }
        System.out.printf("Deletes %d + Updates %d = %d Old = %d\n", deleteCount, updateCount, deleteCount + updateCount, oldIndex.size());
        if (deleteCount + updateCount != oldIndex.size()) {
            throw new Exception("Counts do not match for Old Files");
        }

    }

    private static void setDispositions(List<CVRSIndex> newIndex, List<CVRSIndex> oldIndex) {
        int o = 0, n = 0;
        CVRSIndex oldOne = oldIndex.get(0),
                  newOne = newIndex.get(0);

        while (o < oldIndex.size() || n < newIndex.size()) {

            int result = 0;
            if (newOne == null) {
                result = 1;
            } else if (oldOne == null) {
                result = -1;
            } else {
				result = newOne.compareCVRSTo(oldOne);
            }

            CVRSIndex lastNew = newOne, lastOld = oldOne;
            if (result <= 0) {
                if (result != 0) {
                    newOne.setDestination(CVRSIndex.ADD);
                    disposedAsAdd++;
                }
                newOne = ++n < newIndex.size() ? newIndex.get(n) : null;
            }
            if (result >= 0) {
                if (result != 0) {
                    oldOne.setDestination(CVRSIndex.DELETE);
                    disposedAsDelete++;
                }
                oldOne = ++o < oldIndex.size() ? oldIndex.get(o) : null;
            }
            if (result == 0) {
                // The patient and vaxination id are the same, this record must be an update.
                lastNew.setDestination(CVRSIndex.UPDATE);
                lastOld.setDestination(CVRSIndex.OLD);
                disposedAsUpdate++;
            }
        }
    }

    private static int readCVRSFromFiles(List<File> newFiles, List<CVRSIndex> index, boolean validate) throws IOException, FileNotFoundException {
        int fileIndex = 0;
        for (File f: newFiles) {
            System.out.printf("Processing file %03d: %s ", fileIndex+1, f);
            int filePos = 0;

            try (Validator v = new Validator( new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8), getValidator(validate), false);) {
                v.setReport(System.out);
                v.setName(f.getName());
                if (!validate) {

                }
                while (v.hasNext()) {
                    CVRSExtract e = v.next();
                    CVRSIndex i = new CVRSIndex();
                    i.setPatientId(e.getRecip_id());
                    i.setVaxId(e.getVax_event_id());
                    i.setFileIndex(fileIndex);
                    i.setFilePos(filePos++);
                    index.add(i);
                    if (filePos % 1000 == 0) {
                        System.out.print(".");
                    }
                }
                System.out.println();
            }
            ++fileIndex;
        }
        return index.size();
    }

    private static BeanValidator getValidator(boolean validate) {
        BeanValidator beanValidator =
            validate ? new BeanValidator(Collections.emptySet(), Validator.DEFAULT_VERSION, false) :
                new NullValidator(Validator.ERROR_CODES, Validator.DEFAULT_VERSION);
        return beanValidator;
    }

    private static void createFiles(List<File> files, List<CVRSIndex> index, File outputFolder, boolean validate) throws Exception {
        // Sort indexes by file position
        Collections.sort(index, CVRSIndex::sortByFileAndPosition);
        int fileNo = -1, filePos = 0;
        File f = null;
        Validator v = null;
        Writer  updateFile = null,
                deleteFile = null,
                addFile = null;

        for (CVRSIndex i: index) {
            if (i.getDestination() == CVRSIndex.UNKNOWN) {
                // This record does not have a destination.
                continue;
            }

            if (i.getFileIndex() != fileNo) {
                f = files.get(fileNo = i.getFileIndex());
                filePos = 0;
                v = new Validator( new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8), getValidator(validate), false);
                v.setName(f.getName());
            }

            CVRSExtract e = null;
            do {
                e = v.next();
            } while (filePos++ != i.getFilePos());

            if (filePos != i.getFilePos() + 1) {
                // something nasty has happened.
                throw new Exception(String.format("Index mismatch error on %s, expected record %d but got %d", f.getName(), i.getFileIndex(), filePos - 1));
            }

            // Verify e matches expected i
            if (!i.getPatientId().equals(e.getRecip_id())) {
                printTotals(System.err);
                throw new Exception(String.format("Patient ID does not match, expected %s but found %s", i.getPatientId(), e.getRecip_id()));
            }
            if (!i.getVaxId().equals(e.getVax_event_id())) {
                printTotals(System.err);
                throw new Exception(String.format("Vaxination ID does not match, expected %s but found %s", i.getVaxId(), e.getVax_event_id()));
            }

            // This extract is to be written to a destination file.
            switch (i.getDestination()) {
            case CVRSIndex.UNKNOWN:
                throw new Exception(String.format("Dispatch error on %s, record %d has unknown disposition", f.getName(), i.getFileIndex()));
            case CVRSIndex.ADD:
                addFile = sendUpdate(e, addFile, addCount++, outputFolder, "ADD");
                break;
            case CVRSIndex.DELETE:
                deleteFile = sendUpdate(e, deleteFile, deleteCount++, outputFolder, "DELETE");
                break;
            case CVRSIndex.UPDATE:
                updateFile = sendUpdate(e, updateFile, updateCount++, outputFolder, "UPDATE");
                break;
            case CVRSIndex.OLD:
                // Do nothing, these are records to ignore.
                break;
            }

            if ((addCount + deleteCount + updateCount) % 1000 == 0) {
                printTotals(System.out);
            }
        }
        if (addFile != null) {
            addFile.close();
        }
        if (updateFile != null) {
            updateFile.close();
        }
        if (deleteFile != null) {
            deleteFile.close();
        }
    }

    private static void printTotals(PrintStream out) {
        out.printf("Total: %7d/%7d Adds: %7d/%7d Deletes: %7d/%7d Updates: %7d/%7d\n",
            addCount + deleteCount + updateCount,
            disposedAsAdd + disposedAsDelete + disposedAsUpdate,
            addCount, disposedAsAdd, deleteCount, disposedAsDelete, updateCount, disposedAsUpdate);
    }


    private static Writer sendUpdate(CVRSExtract e, Writer writer, int count, File outputFolder, String fileType) throws IOException {
        if (count % MAX_RECORDS == 0 || writer == null) {
            if (writer != null) {
                writer.close();
            }
            writer = new BufferedWriter(new FileWriter(new File(outputFolder, String.format("%s_%03d", fileType, (count / MAX_RECORDS) + 1))));
            if (count % MAX_RECORDS == 0) {
                // Write the header row.
                Utility.writeRow(writer, headers);
            }
        }
        Utility.writeRow(writer, e.getValues(headers));
        return writer;
    }

    private static boolean checkForSameFile(List<File> newFiles, List<File> oldFiles) throws IOException {
        Collections.sort(newFiles, DeleteUpdateProcessor::filenameComparator);
        Collections.sort(oldFiles, DeleteUpdateProcessor::filenameComparator);
        int n = 0, o = 0;
        File newFile = newFiles.get(n);
        File oldFile = oldFiles.get(o);
        boolean ok = true;
        while (n < newFiles.size() && o < newFiles.size()) {
            int result = newFile.getCanonicalPath().compareTo(oldFile.getCanonicalPath());
            if (result == 0) {
                System.err.printf("%s is in both new and old files", newFile.getCanonicalPath());
                ok = false;
            }
            if (result <= 0) {
                if (++n < newFiles.size()) {
                    newFile = newFiles.get(n);
                }
            }
            if (result >= 0) {
                if (++o < oldFiles.size()) {
                    oldFile = oldFiles.get(o);
                }
            }
        }
        return ok;
    }
    private static int filenameComparator(File newFile, File oldFile) {
        return 0;

    }
    private static List<File> getFilenames(String arg, List<File> files) {
        File f = new File(arg);
        Collection<File> list = FileUtils.listFiles(f.getParentFile(),  new WildcardFileFilter(f.getName(), IOCase.SYSTEM), null);
        files.addAll(list);
        return files;
    }
}
