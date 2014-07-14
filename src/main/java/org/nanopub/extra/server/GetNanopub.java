package org.nanopub.extra.server;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.util.List;

import net.trustyuri.TrustyUriUtils;
import net.trustyuri.rdf.RdfModule;

import org.nanopub.MalformedNanopubException;
import org.nanopub.Nanopub;
import org.nanopub.NanopubImpl;
import org.nanopub.NanopubUtils;
import org.nanopub.trusty.TrustyNanopubUtils;
import org.openrdf.OpenRDFException;
import org.openrdf.model.URI;
import org.openrdf.model.impl.URIImpl;
import org.openrdf.rio.RDFFormat;
import org.openrdf.rio.RDFHandlerException;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.ParameterException;

public class GetNanopub {

	@com.beust.jcommander.Parameter(description = "nanopub-uris-or-artifact-codes", required = true)
	private List<String> nanopubIds;

	@com.beust.jcommander.Parameter(names = "-f", description = "Format of the nanopub: trig, nq, trix, ...")
	private String format = "trig";

	@com.beust.jcommander.Parameter(names = "-d", description = "Save as file(s) in the given directory")
	private File outputDir;

	public static void main(String[] args) {
		GetNanopub obj = new GetNanopub();
		JCommander jc = new JCommander(obj);
		try {
			jc.parse(args);
		} catch (ParameterException ex) {
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

	public static Nanopub get(String uriOrArtifactCode) {
		return get(uriOrArtifactCode, (ServerIterator) null);
	}

	public static Nanopub get(String uriOrArtifactCode, ServerIterator serverIterator) {
		if (serverIterator == null) {
			serverIterator = new ServerIterator();
		}
		String ac = getArtifactCode(uriOrArtifactCode);
		if (!ac.startsWith(RdfModule.MODULE_ID)) {
			throw new IllegalArgumentException("Not a trusty URI of type RA");
		}
		while (serverIterator.hasNext()) {
			String serverUrl = serverIterator.next();
			try {
				Nanopub np = get(ac, serverUrl);
				if (np != null) {
					return np;
				}
			} catch (IOException ex) {
				// ignore
			} catch (OpenRDFException ex) {
				// ignore
			} catch (MalformedNanopubException ex) {
				// ignore
			}
		}
		return null;
	}

	public static Nanopub get(String artifactCode, String serverUrl)
			throws IOException, OpenRDFException, MalformedNanopubException {
		URL url = new URL(serverUrl + artifactCode);
		Nanopub nanopub = new NanopubImpl(url);
		if (TrustyNanopubUtils.isValidTrustyNanopub(nanopub)) {
			return nanopub;
		}
		return null;
	}

	public static String getArtifactCode(String uriOrArtifactCode) {
		if (uriOrArtifactCode.indexOf(":") > 0) {
			URI uri = new URIImpl(uriOrArtifactCode);
			if (!TrustyUriUtils.isPotentialTrustyUri(uri)) {
				throw new IllegalArgumentException("Not a well-formed trusty URI");
			}
			return TrustyUriUtils.getArtifactCode(uri.toString());
		} else {
			if (!TrustyUriUtils.isPotentialArtifactCode(uriOrArtifactCode)) {
				throw new IllegalArgumentException("Not a well-formed artifact code");
			}
			return uriOrArtifactCode;
		}
	}

	private ServerIterator serverIterator = new ServerIterator();

	public GetNanopub() {
	}

	private void run() throws IOException, RDFHandlerException {
		for (String nanopubId : nanopubIds) {
			Nanopub np = getNanopub(nanopubId);
			if (np == null) {
				System.err.println("NOT FOUND: " + nanopubId);
			} else if (outputDir == null) {
				NanopubUtils.writeToStream(np, System.out, RDFFormat.forFileName("file." + format));
				System.out.print("\n\n");
			} else {
				OutputStream out = new FileOutputStream(new File(outputDir, getArtifactCode(nanopubId) + "." + format));
				NanopubUtils.writeToStream(np, out, RDFFormat.forFileName("file." + format));
				out.close();
			}
		}
	}

	public Nanopub getNanopub(String uriOrArtifactCode) {
		return get(uriOrArtifactCode, serverIterator);
	}

}
