package act.installer.bing;

import act.server.MongoDB;
import com.act.utils.TSVWriter;
import com.mongodb.BasicDBObject;
import com.mongodb.DBCursor;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * This module provide a command line interface to update and export Bing Search results and ranks from the Installer
 * database. It supports two types of input: raw list of InChI and TSV file with an InChI header.
 * Usage (raw input):
 *       sbt 'runMain act.installer.bing.BingSearchRanker
 *                -i /mnt/shared-data/Thomas/bing_ranker/l2chemicalsProductFiltered.txt
 *                -o /mnt/shared-data/Thomas/bing_ranker/l2chemicalsProductFiltered_BingSearchRanker_results.tsv'
 * Usage (TSV input):
 *       sbt 'runMain act.installer.bing.BingSearchRanker
 *                -i /mnt/shared-data/Thomas/bing_ranker/benzene_search_results_wikipedia_20160617T1723.txt.hits
 *                -o /mnt/shared-data/Thomas/bing_ranker/benzene_search_results_wikipedia_BingSearchRanker_results.tsv'
 *                -t
 */

public class BingSearchRanker {

  private static final Logger LOGGER = LogManager.getFormatterLogger(BingSearchRanker.class);

  // Default configuration for the Installer database
  public static final String DEFAULT_HOST = "localhost";
  public static final int DEFAULT_PORT = 27017;
  public static final String INSTALLER_DATABASE = "marvin";

  // Define options for CLI
  public static final String OPTION_INPUT_FILEPATH = "i";
  public static final String OPTION_OUTPUT_FILEPATH = "o";
  public static final String OPTION_TSV_INPUT = "t";

  public static final String HELP_MESSAGE = StringUtils.join(new String[]{
      "This class adds Bing Search results for a list of molecules in the Installer (actv01) database",
      "and exports the results in a TSV format for easy import in Google spreadsheets.",
      "It supports two different input formats: raw list of InChI strings and TSV file with an InChI column.",
      "Default input format (with only options -i and -o) is raw list of InChI."
  }, " ");

  public static final List<Option.Builder> OPTION_BUILDERS = new ArrayList<Option.Builder>() {{
    add(Option.builder(OPTION_INPUT_FILEPATH)
        .argName("INPUT_FILEPATH")
        .desc("The full path to the input file")
        .hasArg()
        .required()
        .longOpt("input_filepath")
        .type(String.class)
    );
    add(Option.builder(OPTION_OUTPUT_FILEPATH)
        .argName("OUTPUT_PATH")
        .desc("The full path where to write the output.")
        .hasArg()
        .required()
        .longOpt("output_path")
        .type(String.class)
    );
    add(Option.builder(OPTION_TSV_INPUT)
        .argName("TSV_INPUT")
        .desc("Whether the input is a TSV file with an InChI column.")
        .longOpt("tsv")
        .type(boolean.class)
    );
    add(Option.builder("h")
        .argName("help")
        .desc("Prints this help message")
        .longOpt("help")
    );
  }};

  public static final HelpFormatter HELP_FORMATTER = new HelpFormatter();

  static {
    HELP_FORMATTER.setWidth(100);
  }

  public enum BingRankerHeaderFields {
    INCHI,
    BEST_NAME,
    TOTAL_COUNT_SEARCH_RESULTS,
    ALL_NAMES,
    DEPTH,
    ROOT_MOLECULE,
    TOTAL_COUNT_SEARCH_RESULTS_ROOT
  }

  // Instance variables
  private MongoDB mongoDB;
  private BingSearcher bingSearcher;

  public BingSearchRanker() {
    mongoDB = new MongoDB(DEFAULT_HOST, DEFAULT_PORT, INSTALLER_DATABASE);
    bingSearcher = new BingSearcher();
  }

  public static void main(final String[] args) throws Exception {

    // Parse the command line options
    Options opts = new Options();
    for (Option.Builder b : OPTION_BUILDERS) {
      opts.addOption(b.build());
    }

    CommandLine cl = null;
    try {
      CommandLineParser parser = new DefaultParser();
      cl = parser.parse(opts, args);
    } catch (ParseException e) {
      System.err.format("Argument parsing failed: %s\n", e.getMessage());
      HELP_FORMATTER.printHelp(BingSearchRanker.class.getCanonicalName(), HELP_MESSAGE, opts, null, true);
      System.exit(1);
    }

    if (cl.hasOption("help")) {
      HELP_FORMATTER.printHelp(BingSearchRanker.class.getCanonicalName(), HELP_MESSAGE, opts, null, true);
      return;
    }

    String inputPath = cl.getOptionValue(OPTION_INPUT_FILEPATH);
    String outputPath = cl.getOptionValue(OPTION_OUTPUT_FILEPATH);
    Boolean isTSVInput = cl.hasOption(OPTION_TSV_INPUT);

    // Read the molecule corpus
    LOGGER.info("Reading the input molecule corpus");
    MoleculeCorpus moleculeCorpus = new MoleculeCorpus();
    if (isTSVInput) {
      LOGGER.info("Input format is TSV");
      moleculeCorpus.buildCorpusFromTSVFile(inputPath);
    } else {
      LOGGER.info("Input format is raw InChIs");
      moleculeCorpus.buildCorpusFromRawInchis(inputPath);
    }

    // Get the inchi set
    Set<String> inchis = moleculeCorpus.getMolecules();
    LOGGER.info("Found %d molecules in the input corpus", inchis.size());

    // Update the Bing Search results in the Installer database
    BingSearchRanker bingSearchRanker = new BingSearchRanker();
    LOGGER.info("Updating the Bing Search results in the Installer database");
    bingSearchRanker.addBingSearchResults(inchis);
    LOGGER.info("Done updating the Bing Search results");

    // Write the results in a TSV file
    LOGGER.info("Writing results to output file");
    bingSearchRanker.writeBingSearchRanksAsTSV(inchis, outputPath);
    LOGGER.info("Bing Search ranker is done. \"I'm tired, boss.\"");
  }


  /**
   * This function parses the InChI from a BasicDBObject
   * @param c BasicDBObject extracted from the Installer database
   * @return InChI string
   */
  public String parseInchi(BasicDBObject c) {
    String inchi = (String) c.get("InChI");
    return inchi;
  }

  /**
   * This function parses the Bing Search results count from a BasicDBObject representing Bing metadata
   * @param c BasicDBObject representing Bing metadata
   * @return the Bing Search results count
   */
  public Long parseCountFromBingMetadata(BasicDBObject c) {
    Long totalCountSearchResults = (Long) c.get("total_count_search_results");
    return totalCountSearchResults;
  }

  /**
   * This function parses the best name from a BasicDBObject representing Bing metadata
   * @param c BasicDBObject representing Bing metadata
   * @return the best name
   */
  public String parseNameFromBingMetadata(BasicDBObject c) {
    String bestName = (String) c.get("best_name");
    return bestName;
  }

  /**
   * This function add the Bing Search results to the installer database from a set of InChI strings
   * @param inchis set of InChI string representations
   */
  public void addBingSearchResults(Set<String> inchis) throws IOException {
    bingSearcher.addBingSearchResultsForInchiSet(mongoDB, inchis);
  }

  /**
   * This function writes the Bing Search ranks for a specific set of inchis in a TSV file.
   * @param inchis set of InChI string representations
   * @param outputPath path indicating the output file
   * @throws IOException
   */
  public void writeBingSearchRanksAsTSV(Set<String> inchis, String outputPath) throws IOException {

    // Define headers
    List<String> bingRankerHeaderFields = new ArrayList<String>() {{
      add(BingRankerHeaderFields.INCHI.name());
      add(BingRankerHeaderFields.BEST_NAME.name());
      add(BingRankerHeaderFields.TOTAL_COUNT_SEARCH_RESULTS.name());
      add(BingRankerHeaderFields.ALL_NAMES.name());
    }};

    // Open TSV writer
    TSVWriter tsvWriter = new TSVWriter(bingRankerHeaderFields);
    tsvWriter.open(new File(outputPath));

    int counter = 0;
    DBCursor cursor = mongoDB.fetchNamesAndBingInformationForInchis(inchis);

    // Iterate through the target chemicals
    while (cursor.hasNext()) {
      counter++;
      BasicDBObject o = (BasicDBObject) cursor.next();
      String inchi = parseInchi(o);
      Map<String, String> row = new HashMap<>();
      row.put(BingRankerHeaderFields.INCHI.name(), inchi);
      BasicDBObject xref = (BasicDBObject) o.get("xref");
      BasicDBObject bing = (BasicDBObject) xref.get("BING");
      BasicDBObject metadata = (BasicDBObject) bing.get("metadata");
      row.put(BingRankerHeaderFields.BEST_NAME.name(), parseNameFromBingMetadata(metadata));
      row.put(BingRankerHeaderFields.TOTAL_COUNT_SEARCH_RESULTS.name(), parseCountFromBingMetadata(metadata).toString());
      NamesOfMolecule namesOfMolecule = mongoDB.getNamesFromBasicDBObject(o);
      Set<String> names = namesOfMolecule.getBrendaNames();
      names.addAll(namesOfMolecule.getMetacycNames());
      names.addAll(namesOfMolecule.getChebiNames());
      names.addAll(namesOfMolecule.getDrugbankNames());
      row.put(BingRankerHeaderFields.ALL_NAMES.name(), names.toString());
      tsvWriter.append(row);
    }
    tsvWriter.flush();
    tsvWriter.close();
    LOGGER.info("Wrote %d Bing Search results to %s", counter, outputPath);
  }

  /**
   * This function is used to write out the conditional reachability results with data on target chemical, root chemical,
   * depth of steps from root to target chemical, the bing search results, all the other names associated with the target
   * and inchi of the target in a tsv file. This function is not scalable since it has to have an in-memory representation
   * of the target and root molecule's bing results to input the data into the TSV file.
   * @param allInchis - All the inchis that are to be analyzed
   * @param descendantToRoot - mapping of chemical to its root chemical in the conditional reachability tree
   * @param pairOfRootAndDescendantInchisToDepth - pair of root and descendant inchi to the descendant inchi's depth from
   *                                             the root. We have to use the pair structure since the descent inchi is not
   *                                             unique, ie. it can be associated with many roots.
   * @param outputPath - The output path of the tsv file.
   * @throws IOException
   */
  public void writeBingSearchRanksAsTSVUsingConditionalReachabilityFormat(
      Set<String> allInchis,
      Map<String, String> descendantToRoot,
      Map<Pair<String, String>, Integer> pairOfRootAndDescendantInchisToDepth,
      String outputPath) throws IOException {

    // Define headers
    List<String> bingRankerHeaderFields = new ArrayList<String>() {{
      add(BingRankerHeaderFields.INCHI.name());
      add(BingRankerHeaderFields.BEST_NAME.name());
      add(BingRankerHeaderFields.TOTAL_COUNT_SEARCH_RESULTS.name());
      add(BingRankerHeaderFields.ALL_NAMES.name());
      add(BingRankerHeaderFields.DEPTH.name());
      add(BingRankerHeaderFields.ROOT_MOLECULE.name());
    }};

    // Gather all inchis from both the root and it's descendants
    // TODO: We have to do an in-memory calculation of all the inchis since we need to pair up the child and root
    // inchis. This does take up a lot of memory.
    Map<String, BasicDBObject> inchiToDBObject = new HashMap<>();

    LOGGER.info("Gathering all the inchis.");
    Set<String> inchis = new HashSet<>();
    for (Map.Entry<String, String> desToRoot : descendantToRoot.entrySet()) {
      inchis.add(desToRoot.getKey());
      inchis.add(desToRoot.getValue());
    }
    LOGGER.info("The total number of inchis are: %d", inchis.size());

    LOGGER.info("Creating mappings between inchi and it's DB object");
    DBCursor cursor = mongoDB.fetchNamesAndBingInformationForInchis(inchis);
    int cursorCounter = 0;
    while (cursor.hasNext()) {
      cursorCounter++;
      BasicDBObject o = (BasicDBObject) cursor.next();
      String inchi = parseInchi(o);

      if (inchi == null) {
        LOGGER.error("Inchi could not be parsed.");
      }

      inchiToDBObject.put(inchi, o);
    }

    LOGGER.info("The total number of inchis found in the db is: %d", cursorCounter);

    // Open TSV writer
    LOGGER.info("Going to write to TSV file.");
    try (TSVWriter<String, String> tsvWriter = new TSVWriter<>(bingRankerHeaderFields)) {
      tsvWriter.open(new File(outputPath));

      BingSearchResults bingSearchResults = new BingSearchResults();
      int counter = 0;

      LOGGER.info("Compute each row.");
      for (String descendentInchi : descendantToRoot.keySet()) {
        Map<String, String> row = new HashMap<>();

        // Add all the descendant field results
        BasicDBObject descendentDBObject = inchiToDBObject.get(descendentInchi);
        row.put(BingRankerHeaderFields.INCHI.name(), descendentInchi);
        BasicDBObject xref = (BasicDBObject) descendentDBObject.get("xref");
        BasicDBObject bing = (BasicDBObject) xref.get("BING");
        BasicDBObject metadata = (BasicDBObject) bing.get("metadata");
        row.put(BingRankerHeaderFields.BEST_NAME.name(), parseNameFromBingMetadata(metadata));
        row.put(BingRankerHeaderFields.TOTAL_COUNT_SEARCH_RESULTS.name(), parseCountFromBingMetadata(metadata).toString());
        NamesOfMolecule namesOfMolecule = mongoDB.getNamesFromBasicDBObject(descendentDBObject);
        Set<String> names = namesOfMolecule.getBrendaNames();
        names.addAll(namesOfMolecule.getMetacycNames());
        names.addAll(namesOfMolecule.getChebiNames());
        names.addAll(namesOfMolecule.getDrugbankNames());
        row.put(BingRankerHeaderFields.ALL_NAMES.name(), names.toString());

        // Add all the root field results
        String rootInchi = descendantToRoot.get(descendentInchi);
        NamesOfMolecule namesOfRootMolecule = mongoDB.fetchNamesFromInchi(rootInchi);
        if (namesOfRootMolecule == null) {
          row.put(BingRankerHeaderFields.ROOT_MOLECULE.name(), "");
        } else {
          // Chooses the best name according to Bing search results
          String bestNameOfRoot = bingSearchResults.findBestMoleculeName(namesOfRootMolecule);
          row.put(BingRankerHeaderFields.ROOT_MOLECULE.name(), bestNameOfRoot);
        }
        BasicDBObject rootDBObject = inchiToDBObject.get(rootInchi);
        if (rootDBObject != null) {
          BasicDBObject rootXref = (BasicDBObject) rootDBObject.get("xref");
          BasicDBObject rootBing = (BasicDBObject) rootXref.get("BING");
          BasicDBObject rootMetadata = (BasicDBObject) rootBing.get("metadata");
          row.put(BingRankerHeaderFields.TOTAL_COUNT_SEARCH_RESULTS_ROOT.name(), parseCountFromBingMetadata(rootMetadata).toString());
        } else {
          row.put(BingRankerHeaderFields.TOTAL_COUNT_SEARCH_RESULTS_ROOT.name(), "0");
        }
        row.put(BingRankerHeaderFields.DEPTH.name(),
            pairOfRootAndDescendantInchisToDepth.get(Pair.of(rootInchi, descendentInchi)).toString());

        tsvWriter.append(row);
        tsvWriter.flush();
        counter++;
      }

      LOGGER.info("Wrote %d rows to %s", counter, outputPath);
    }
  }
}
