package org.nanopub.extra.index;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.nanopub.MalformedNanopubException;
import org.nanopub.MultiNanopubRdfHandler;
import org.nanopub.MultiNanopubRdfHandler.NanopubHandler;
import org.nanopub.Nanopub;
import org.nanopub.NanopubImpl;
import org.nanopub.NanopubUtils;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.ParameterException;

public class MakeIndex {

	@com.beust.jcommander.Parameter(description = "input-nanopub-files")
	private List<File> inputFiles = new ArrayList<File>();

	@com.beust.jcommander.Parameter(names = "-fs", description = "Add index nanopubs from input files " +
			"as sub-indexes (instead of elements); has no effect if input file is plain-text list of URIs")
	private boolean useSubindexes = false;

	@com.beust.jcommander.Parameter(names = "-e", description = "Add given URIs as elements " +
			"(in addition to the ones from the input files)")
	private List<String> elements = new ArrayList<>();

	@com.beust.jcommander.Parameter(names = "-s", description = "Add given URIs as sub-indexes " +
			"(in addition to the ones from the input files, if given)")
	private List<String> subindexes = new ArrayList<>();

	@com.beust.jcommander.Parameter(names = "-x", description = "Set given URI as superseded index")
	private String supersededIndex;

	@com.beust.jcommander.Parameter(names = "-o", description = "Output file")
	private File outputFile = new File("index.trig");

	@com.beust.jcommander.Parameter(names = "-u", description = "Base URI for index nanopubs")
	private String baseUri = "http://purl.org/np/";

	@com.beust.jcommander.Parameter(names = "-t", description = "Title of index")
	private String iTitle;

	@com.beust.jcommander.Parameter(names = "-d", description = "Description of index")
	private String iDesc;

	@com.beust.jcommander.Parameter(names = "-c", description = "Creator of index")
	private List<String> iCreators = new ArrayList<>();

	@com.beust.jcommander.Parameter(names = "-l", description = "License URI")
	private String licenseUri;

	@com.beust.jcommander.Parameter(names = "-a", description = "'See also' resources")
	private List<String> seeAlso = new ArrayList<>();

	public static void main(String[] args) throws IOException {
		NanopubImpl.ensureLoaded();
		MakeIndex obj = new MakeIndex();
		JCommander jc = new JCommander(obj);
		try {
			jc.parse(args);
		} catch (ParameterException ex) {
			jc.usage();
			System.exit(1);
		}
		if (obj.inputFiles.isEmpty() && obj.elements.isEmpty() && obj.subindexes.isEmpty() && obj.supersededIndex == null) {
			jc.usage();
			System.exit(1);
		}
		try {
			obj.run();
		} catch (Exception ex) {
			ex.printStackTrace();
			System.exit(1);
		}
	}

	private SimpleIndexCreator indexCreator;
	private OutputStreamWriter writer;
	private RDFFormat outFormat;
	private int count;

	private MakeIndex() {
	}

	private void init() throws IOException {
		count = 0;
		outFormat = Rio.getParserFormatForFileName(outputFile.getName()).orElse(null);
		if (outputFile.getName().endsWith(".gz")) {
			writer = new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(outputFile)), Charset.forName("UTF-8"));
		} else {
			writer = new OutputStreamWriter(new FileOutputStream(outputFile), Charset.forName("UTF-8"));
		}

		indexCreator = new SimpleIndexCreator() {

			@Override
			public void handleIncompleteIndex(NanopubIndex npi) {
				try {
					writer.write(NanopubUtils.writeToString(npi, outFormat) + "\n\n");
				} catch (Exception ex) {
					throw new RuntimeException(ex);
				}
			}

			@Override
			public void handleCompleteIndex(NanopubIndex npi) {
				System.out.println("Index URI: " + npi.getUri());
				try {
					writer.write(NanopubUtils.writeToString(npi, outFormat) + "\n\n");
				} catch (Exception ex) {
					throw new RuntimeException(ex);
				}
			}

		};

		indexCreator.setBaseUri(baseUri);
		if (iTitle != null) {
			indexCreator.setTitle(iTitle);
		}
		if (iDesc != null) {
			indexCreator.setDescription(iDesc);
		}
		for (String creator : iCreators) {
			indexCreator.addCreator(creator);
		}
		if (licenseUri != null) {
			indexCreator.setLicense(SimpleValueFactory.getInstance().createIRI(licenseUri));
		}
		for (String sa : seeAlso) {
			indexCreator.addSeeAlsoUri(SimpleValueFactory.getInstance().createIRI(sa));
		}
	}

	private void run() throws Exception {
		init();
		for (File f : inputFiles) {
			if (f.getName().endsWith(".txt")) {
				BufferedReader br = null;
				try {
					br = new BufferedReader(new FileReader(f));
				    String line;
				    while ((line = br.readLine()) != null) {
				    	line = line.trim();
				    	if (line.isEmpty()) continue;
				    	// To allow for other content in the file, ignore everything after the first blank space:
				    	if (line.contains(" ")) line = line.substring(0, line.indexOf(" "));
				    	indexCreator.addElement(SimpleValueFactory.getInstance().createIRI(line));
				    }
				} finally {
					if (br != null) br.close();
				}
			} else {
				RDFFormat format = Rio.getParserFormatForFileName(f.getName()).orElse(null);
				MultiNanopubRdfHandler.process(format, f, new NanopubHandler() {
					@Override
					public void handleNanopub(Nanopub np) {
						if (useSubindexes && IndexUtils.isIndex(np)) {
							try {
								indexCreator.addSubIndex(IndexUtils.castToIndex(np));
							} catch (MalformedNanopubException ex) {
								throw new RuntimeException(ex);
							}
						} else {
							indexCreator.addElement(np);
						}
						count++;
						if (count % 100 == 0) {
							System.err.print(count + " nanopubs...\r");
						}
					}
				});
			}
		}
		for (String e : elements) {
			indexCreator.addElement(SimpleValueFactory.getInstance().createIRI(e));
		}
		for (String s : subindexes) {
			indexCreator.addSubIndex(SimpleValueFactory.getInstance().createIRI(s));
		}
		if (supersededIndex != null) {
			indexCreator.setSupersededIndex(SimpleValueFactory.getInstance().createIRI(supersededIndex));
		}
		indexCreator.finalizeNanopub();
		writer.close();
	}

}
