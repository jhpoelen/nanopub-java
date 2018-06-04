package org.nanopub.extra.security;

import static org.nanopub.extra.security.NanopubSignatureElement.HAS_ALGORITHM;
import static org.nanopub.extra.security.NanopubSignatureElement.HAS_PUBLIC_KEY;
import static org.nanopub.extra.security.NanopubSignatureElement.HAS_SIGNATURE;
import static org.nanopub.extra.security.NanopubSignatureElement.HAS_SIGNATURE_TARGET;
import static org.nanopub.extra.security.NanopubSignatureElement.SIGNED_BY;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.KeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.List;

import javax.xml.bind.DatatypeConverter;

import org.nanopub.MalformedNanopubException;
import org.nanopub.Nanopub;
import org.nanopub.NanopubRdfHandler;
import org.nanopub.NanopubUtils;
import org.nanopub.NanopubWithNs;
import org.openrdf.model.Literal;
import org.openrdf.model.Statement;
import org.openrdf.model.URI;
import org.openrdf.model.impl.ContextStatementImpl;
import org.openrdf.model.impl.LiteralImpl;
import org.openrdf.model.impl.URIImpl;
import org.openrdf.rio.RDFFormat;
import org.openrdf.rio.RDFHandlerException;

import net.trustyuri.TrustyUriException;
import net.trustyuri.TrustyUriUtils;
import net.trustyuri.rdf.RdfFileContent;
import net.trustyuri.rdf.RdfHasher;
import net.trustyuri.rdf.RdfPreprocessor;
import net.trustyuri.rdf.TransformRdf;

// TODO: nanopub signatures are being updated...

public class SignatureUtils {

	private SignatureUtils() {}  // no instances allowed

	public static NanopubSignatureElement getSignatureElement(Nanopub nanopub) throws MalformedSignatureException {
		URI signatureUri = getSignatureElementUri(nanopub);
		if (signatureUri == null) return null;
		NanopubSignatureElement se = new NanopubSignatureElement(nanopub.getUri(), signatureUri);

		for (Statement st : nanopub.getHead()) se.addTargetStatement(st);
		for (Statement st : nanopub.getAssertion()) se.addTargetStatement(st);
		for (Statement st : nanopub.getProvenance()) se.addTargetStatement(st);

		for (Statement st : nanopub.getPubinfo()) {
			if (!st.getSubject().equals(signatureUri)) {
				se.addTargetStatement(st);
				continue;
			}
			if (st.getPredicate().equals(NanopubSignatureElement.HAS_SIGNATURE)) {
				// This statement is the only one that is *not* added as a target statement
				if (!(st.getObject() instanceof Literal)) {
					throw new MalformedSignatureException("Literal expected as signature: " + st.getObject());
				}
				se.setSignatureLiteral((Literal) st.getObject());
			} else {
				se.addTargetStatement(st);
				if (st.getPredicate().equals(NanopubSignatureElement.HAS_PUBLIC_KEY)) {
					if (!(st.getObject() instanceof Literal)) {
						throw new MalformedSignatureException("Literal expected as public key: " + st.getObject());
					}
					se.setPublicKeyLiteral((Literal) st.getObject());
				} else if (st.getPredicate().equals(NanopubSignatureElement.HAS_ALGORITHM)) {
					if (!(st.getObject() instanceof Literal)) {
						throw new MalformedSignatureException("Literal expected as algorithm: " + st.getObject());
					}
					se.setAlgorithm((Literal) st.getObject());
				} else if (st.getPredicate().equals(NanopubSignatureElement.SIGNED_BY)) {
					if (!(st.getObject() instanceof URI)) {
						throw new MalformedSignatureException("URI expected as signer: " + st.getObject());
					}
					se.addSigner((URI) st.getObject());
				}
				// We ignore other type of information at this point, but can consider it in the future.
			}
		}
		if (se.getSignature() == null) {
			throw new MalformedSignatureException("Signature element without signature");
		}
		if (se.getAlgorithm() == null) {
			throw new MalformedSignatureException("Signature element without algorithm");
		}
		if (se.getPublicKeyString() == null) {
			// We require a full public key for now, but plan to support public key fingerprints as an alternative.
			throw new MalformedSignatureException("Signature element without public key");
		}
		return se;
	}

	public static boolean hasValidSignature(NanopubSignatureElement se) throws GeneralSecurityException {
		String artifactCode = TrustyUriUtils.getArtifactCode(se.getTargetNanopubUri().toString());
		List<Statement> statements = RdfPreprocessor.run(se.getTargetStatements(), artifactCode);
		Signature signature = Signature.getInstance("SHA256with" + se.getAlgorithm().name());
		KeySpec publicSpec = new X509EncodedKeySpec(DatatypeConverter.parseBase64Binary(se.getPublicKeyString()));
		PublicKey publicKey = KeyFactory.getInstance(se.getAlgorithm().name()).generatePublic(publicSpec);
		signature.initVerify(publicKey);

//		System.err.println("SIGNATURE INPUT: ---");
//		System.err.print(RdfHasher.getDigestString(statements));
//		System.err.println("---");

		signature.update(RdfHasher.getDigestString(statements).getBytes());
		return signature.verify(se.getSignature());
	}

	public static Nanopub createSignedNanopub(Nanopub preNanopub, SignatureAlgorithm algorithm, KeyPair key, URI signer)
			throws GeneralSecurityException, RDFHandlerException, TrustyUriException, MalformedNanopubException {
		// TODO: Test this

		Signature signature = Signature.getInstance("SHA256with" + algorithm.name());
		signature.initSign(key.getPrivate());

		List<Statement> preStatements = NanopubUtils.getStatements(preNanopub);

		// Adding signature element:
		URI signatureElUri = new URIImpl(preNanopub.getUri() + "sig");
		URI npUri = preNanopub.getUri();
		URI piUri = preNanopub.getPubinfoUri();
		String publicKeyString = DatatypeConverter.printBase64Binary(key.getPublic().getEncoded()).replaceAll("\\s", "");
		Literal publicKeyLiteral = new LiteralImpl(publicKeyString);
		preStatements.add(new ContextStatementImpl(signatureElUri, HAS_SIGNATURE_TARGET, npUri, piUri));
		preStatements.add(new ContextStatementImpl(signatureElUri, HAS_PUBLIC_KEY, publicKeyLiteral, piUri));
		Literal algorithmLiteral = new LiteralImpl(algorithm.name());
		preStatements.add(new ContextStatementImpl(signatureElUri, HAS_ALGORITHM, algorithmLiteral, piUri));
		if (signer != null) {
			preStatements.add(new ContextStatementImpl(signatureElUri, SIGNED_BY, signer, piUri));
		}

		// Preprocess statements that are covered by signature:
		List<Statement> preprocessedStatements = RdfPreprocessor.run(preStatements, preNanopub.getUri());

		// Create signature:
		signature.update(RdfHasher.getDigestString(preprocessedStatements).getBytes());
		byte[] signatureBytes = signature.sign();
		Literal signatureLiteral = new LiteralImpl(DatatypeConverter.printBase64Binary(signatureBytes));

		// Preprocess signature statement:
		List<Statement> sigStatementList = new ArrayList<Statement>();
		sigStatementList.add(new ContextStatementImpl(signatureElUri, HAS_SIGNATURE, signatureLiteral, piUri));
		Statement preprocessedSigStatement = RdfPreprocessor.run(sigStatementList, preNanopub.getUri()).get(0);

		// Combine all statements:
		RdfFileContent signedContent = new RdfFileContent(RDFFormat.TRIG);
		signedContent.startRDF();
		if (preNanopub instanceof NanopubWithNs) {
			NanopubWithNs preNanopubNs = (NanopubWithNs) preNanopub;
			for (String prefix : preNanopubNs.getNsPrefixes()) {
				signedContent.handleNamespace(prefix, preNanopubNs.getNamespace(prefix));
			}
		}
		signedContent.handleNamespace("npx", "http://purl.org/nanopub/x/");
		for (Statement st : preprocessedStatements) {
			signedContent.handleStatement(st);
		}
		signedContent.handleStatement(preprocessedSigStatement);
		signedContent.endRDF();

		// Create nanopub object:
		NanopubRdfHandler nanopubHandler = new NanopubRdfHandler();
		TransformRdf.transformPreprocessed(signedContent, preNanopub.getUri(), nanopubHandler);
		return nanopubHandler.getNanopub();
	}

	private static URI getSignatureElementUri(Nanopub nanopub) throws MalformedSignatureException {
		URI signatureElementUri = null;
		for (Statement st : nanopub.getPubinfo()) {
			if (!st.getPredicate().equals(NanopubSignatureElement.HAS_SIGNATURE_TARGET)) continue;
			if (!st.getObject().equals(nanopub.getUri())) continue;
			if (!(st.getSubject() instanceof URI)) {
				throw new MalformedSignatureException("Signature element must be identified by URI");
			}
			if (signatureElementUri != null) {
				throw new MalformedSignatureException("Multiple signature elements found");
			}
			signatureElementUri = (URI) st.getSubject();
		}
		return signatureElementUri;
	}

	/**
	 * This includes legacy signatures. Might include false positives.
	 */
	public static boolean seemsToHaveSignature(Nanopub nanopub) {
		for (Statement st : nanopub.getPubinfo()) {
			if (st.getPredicate().equals(NanopubSignatureElement.HAS_SIGNATURE_ELEMENT)) return true;
			if (st.getPredicate().equals(NanopubSignatureElement.HAS_SIGNATURE_TARGET)) return true;
			if (st.getPredicate().equals(NanopubSignatureElement.HAS_SIGNATURE)) return true;
			if (st.getPredicate().equals(NanopubSignatureElement.HAS_PUBLIC_KEY)) return true;
		}
		return false;
	}

}
