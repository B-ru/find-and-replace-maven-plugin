package io.github.floverfelt.find.and.replace.maven.plugin.tasks;

import org.apache.maven.plugin.logging.Log;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ProcessFilesTask {

  private ProcessFilesTask() {
    // do not instantiate
  }

  /**
   * Stupid simple implementation of file walking, renaming, etc.
   *
   * @param log                   the maven-plugin log
   * @param baseDir               the directory to start in
   * @param isRecursive           whether to recurse further
   * @param findRegex             the regex to find
   * @param replaceValue          the value to replace the found regex
   * @param fileMasks             the file masks to use
   * @param exclusions            the filename regex to exclude
   * @param processFileContents   whether to process file contents
   * @param processFilenames      whether to process file names
   * @param processDirectoryNames whether to process directory names
   * @param charset               encoding to be used when reading files
   */
  public static void process(Log log, Path baseDir, boolean isRecursive, Pattern findRegex, String replaceValue,
                             List<String> fileMasks, List<Pattern> exclusions, boolean processFileContents,
                             boolean processFilenames, boolean processDirectoryNames, boolean replaceAll, Charset charset) throws IOException {

    // Load in the files in the base dir
    File[] baseDirFiles = new File(baseDir.toUri()).listFiles();
    if (baseDirFiles == null) {
      throw new IOException(String.format("Unable to list file(s) in baseDir='%s'", baseDir));
    }
    List<File> filesToProcess = new ArrayList<>(Arrays.asList(baseDirFiles));

    ListIterator<File> iterator = filesToProcess.listIterator();

    while (iterator.hasNext()) {

      File file = iterator.next();

      // Remove the file from processing
      iterator.remove();

      // Perform dir checks
      if (file.isDirectory()) {
        iterator = processDirectory(iterator, isRecursive, file, processDirectoryNames, exclusions, findRegex, replaceValue, replaceAll, log);
      }

      if (file.isFile()) {
        processFile(log, exclusions, file, fileMasks, processFileContents, findRegex, replaceValue, processFilenames, replaceAll, charset);
      }

    }

  }


  private static ListIterator<File> processDirectory(ListIterator<File> iterator, boolean isRecursive, File file,
                                                     boolean processDirectoryNames, List<Pattern> exclusions, Pattern findRegex,
                                                     String replaceValue, boolean replaceAll, Log log) throws IOException {

    // Rename the directory
    if (processDirectoryNames && !shouldExcludeFile(exclusions, file)) {
      file = renameFile(log, file, findRegex, replaceValue, replaceAll);
    }

    // If recursive, add child files to iterator
    if (isRecursive) {
      File[] filesToAdd = file.listFiles();
      assert filesToAdd != null;
      for (File f : filesToAdd) {
        iterator.add(f);
        iterator.previous();
      }
    }

    return iterator;

  }


  private static boolean shouldExcludeFile(List<Pattern> exclusions, File file) {

    for (Pattern p : exclusions) {
      if (p.matcher(file.getName()).find()) {
        return true;
      }
    }

    return false;

  }

  private static boolean shouldProcessFile(List<String> fileMasks, File file) {

    if (fileMasks.isEmpty()) {
      return true;
    }

    for (String fileMask : fileMasks) {
      if (file.getName().endsWith(fileMask)) {
        return true;
      }
    }

    return false;

  }

  private static File renameFile(Log log, File file, Pattern findRegex, String replaceValue, boolean replaceAll) throws IOException {

    Path filePath = file.toPath();
    Path parentDir = filePath.getParent();
    String oldName = file.getName();
    Matcher matcher = findRegex.matcher(oldName);
    String newName = replaceAll ? matcher.replaceAll(replaceValue) : matcher.replaceFirst(replaceValue);

    if (!newName.equals(oldName)) {
      Path targetPath = Paths.get(parentDir.toString(), newName);

      log.info(String.format("Renaming %s to %s", oldName, newName));

      return new File(Files.move(filePath, targetPath).toUri());

    }

    return file;

  }

  private static void processFileContents(File file, Pattern findRegex, String replaceValue, boolean replaceAll, Charset charset) throws IOException {

    File tempFile = File.createTempFile("tmp", "tmp", file.getParentFile());

    try (FileOutputStream fos = new FileOutputStream(tempFile);
     OutputStreamWriter osr = new OutputStreamWriter(fos, charset);
     BufferedWriter fileWriter = new BufferedWriter(osr)) {

      String fileContent = new String(Files.readAllBytes(file.toPath()), charset);
      Matcher matcher = findRegex.matcher(fileContent);
      if (matcher.find()) {
        if(null == replaceValue) replaceValue = "";
        fileContent = replaceAll ? matcher.replaceAll(replaceValue) : matcher.replaceFirst(replaceValue);
      }

      try {
        fileWriter.write(fileContent);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    if(!applyAccessBits(file, tempFile)){
      throw new RuntimeException("Failed to apply access bits at: " + file.getPath());
    }

    if (!file.delete()) {
      throw new IOException("Failed to delete file at: " + file.getPath());
    }

    if (!tempFile.renameTo(file)) {
      throw new IOException("Failed to rename temp file at: " + tempFile.getPath() + " to " + file.getPath());
    }

  }

  private static void processFile(Log log, List<Pattern> exclusions, File file, List<String> fileMasks,
                                  boolean processFileContents, Pattern findRegex, String replaceValue,
                                  boolean processFilenames, boolean replaceAll, Charset charset) throws IOException {

    if (shouldExcludeFile(exclusions, file) || !shouldProcessFile(fileMasks, file)) {
      return;
    }

    if (processFileContents) {
      processFileContents(file, findRegex, replaceValue, replaceAll, charset);
    }

    if (processFilenames) {
      renameFile(log, file, findRegex, replaceValue, replaceAll);
    }

  }

  private static boolean applyAccessBits(File original, File successor){
    try{
        if(FileSystems.getDefault().supportedFileAttributeViews().contains("posix")){
            Set<PosixFilePermission> permissions = Files.readAttributes(original.toPath(), PosixFileAttributes.class).permissions();
            Files.setPosixFilePermissions(successor.toPath(), permissions);
        } 
        return true;
    } catch(IOException e){
        return false;
    }    
  }
}
