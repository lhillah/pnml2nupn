package fr.lip6.move.pnml2nupn.export.impl;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.stream.Collectors;

import org.slf4j.Logger;

import com.ximpleware.extended.AutoPilotHuge;
import com.ximpleware.extended.NavExceptionHuge;
import com.ximpleware.extended.VTDNavHuge;
import com.ximpleware.extended.XPathEvalExceptionHuge;
import com.ximpleware.extended.XPathParseExceptionHuge;

import fr.lip6.move.pnml2nupn.MainPNML2NUPN;
import fr.lip6.move.pnml2nupn.exceptions.PNMLImportExportException;
import fr.lip6.move.pnml2nupn.utils.PNML2NUPNUtils;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongBigArrayBigList;
import it.unimi.dsi.fastutil.longs.LongCollection;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongRBTreeSet;
import it.unimi.dsi.fastutil.objects.Object2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectBigArrayBigList;

public final class NativeNUPNExtractor {

	private File inFile, outFile;
	private File outTSFile, outPSFile;
	private Logger logger;
	private OutChannelBean ocbNupn, ocbTs, ocbPs;
	private BlockingQueue<String> nupnQueue, tsQueue, psQueue;
	private Thread nupnWriter, tsWriter, psWriter;
	private VTDNavHuge vn;
	private AutoPilotHuge ap;
	private long nbUnits, nbTrans, nbPlaces, nbArcs;
	private String rootUnitId;
	private boolean isSafe;
	private ObjectBigArrayBigList<String> markedPlaces;
	private Object2LongOpenHashMap<String> plId2nupnMap;
	private Object2ObjectOpenHashMap<String, String> plId2NameMap;
	private Object2LongOpenHashMap<String> trId2nupnMap;
	private Object2ObjectOpenHashMap<String, String> trId2NameMap;
	private Long2ObjectOpenHashMap<LongBigArrayBigList> tr2OutPlacesMap;
	private Long2ObjectOpenHashMap<LongBigArrayBigList> tr2InPlacesMap;
	private Object2LongOpenHashMap<String> unitsIdMap;
	private LongBigArrayBigList markedPlacesNupnId;
	private ObjectBigArrayBigList<String> nupnLines;
	private long nupnPlIdGen, nupnUnitIdGen;
	private final StringBuilder nupnsb;
	/* For greatest label length. Since v-3.0.0 */
	private int labelLength;

	public NativeNUPNExtractor(File input, File output, Logger journal) {
		this.inFile = input;
		this.outFile = output;
		this.logger = journal;
		nupnsb = new StringBuilder();
	}

	public void extractNUPN(VTDNavHuge vn, AutoPilotHuge ap)
			throws PNMLImportExportException, InterruptedException, IOException {
		this.vn = vn;
		this.ap = ap;
		extractNUPN();
	}

	public void extractNUPN() throws PNMLImportExportException, InterruptedException, IOException {
		logger.info("Starting the extraction of native NUPN from PNML.");
		logger.info("In this mode, options {} and {} are ignored.", MainPNML2NUPN.USE_PLACE_NAMES,
				MainPNML2NUPN.USE_TRANSITION_NAMES);

		try {
			checkAndSetNavAutopilot();
			initDataStructures();
			openIOChannels();
			startWriters();

			// Insert creator pragma
			PNML2NUPNUtils.insertCreatorPragma(nupnQueue);

			extractSizes();
			extractStructure();
			extractUnits();
			collectInitialPlaces();
			collectNonInitialPlacesNames();

			writeNUPNPlaces();
			writeInitialPlaces();
			writeUnits();
			
			writePlaceLabels();

			collectTransitions();
			writeTransitions();

			// write labels line
			ExportUtils.setLabelsLine(nupnQueue, labelLength, trId2nupnMap.isEmpty());

			// Stop place and transition writers, and release related resources
			PNML2NUPNUtils.stopWriters(tsQueue, psQueue);
			tsWriter.join();
			psWriter.join();
			PNML2NUPNUtils.closeChannels(ocbTs, ocbPs);

			// append contents of place and transition files to NUPN file
			logger.info("Appending place Ids-labels mappings to NUPN file");
			ExportUtils.appendFileContentToNUPN(outPSFile, nupnQueue, logger);
			logger.info("Appending transition Ids-labels mappings to NUPN file");
			ExportUtils.appendFileContentToNUPN(outTSFile, nupnQueue, logger);

			// Stop NUPN writer, and release related resources
			PNML2NUPNUtils.stopWriters(nupnQueue);
			nupnWriter.join();
			PNML2NUPNUtils.closeChannels(ocbNupn);

			// Delete place and transition files
			logger.info("Deleting place and transition Ids-labels mappings files");
			PNML2NUPNUtils.deleteOutputFiles(outPSFile, outTSFile);

			clearDataStructures();
			logger.info("See NUPN file: {}", outFile.getCanonicalPath());

		} catch (InterruptedException | PNMLImportExportException | IOException e) {
			emergencyStop(outFile);
			throw new PNMLImportExportException(e);
		}
	}

	private void collectInitialPlaces() throws PNMLImportExportException {
		logger.info("Collecting initially marked places.");
		String placeId;
		long plNupnId;
		long mkg, totalMkg = 0L;
		ObjectBigArrayBigList<String> unsafePlaces = new ObjectBigArrayBigList<>();

		LongArrayList minMarking = new LongArrayList(2);
		LongArrayList maxMarking = new LongArrayList(2);
		try {
			vn.toElement(VTDNavHuge.ROOT);
			ap.selectXPath(PNMLPaths.MARKED_PLACES);
			while ((ap.evalXPath()) != -1) {
				vn.push();
				vn.toElement(VTDNavHuge.FIRST_CHILD);
				while (!vn.matchElement(NUPNConstants.TEXT)) {
					vn.toElement(VTDNavHuge.NEXT_SIBLING);
				}
				totalMkg += mkg = Long.parseLong(vn.toString(vn.getText()).trim());

				vn.toElement(VTDNavHuge.PARENT);
				vn.toElement(VTDNavHuge.PARENT);
				placeId = ExportUtils.getPNMLNodeId(vn, MainPNML2NUPN.isUsePlaceNames(), plId2NameMap);
				plNupnId = plId2nupnMap.getLong(placeId);
				if (plNupnId == -1L) {
					logger.error("Marked place {} was not reported in the NUPN toolspecific section!", placeId);
				}
				markedPlaces.add(placeId);
				markedPlacesNupnId.add(plNupnId);

				if (mkg > 1) {
					unsafePlaces.add(placeId);
					PNML2NUPNUtils.setMin(mkg, minMarking);
					PNML2NUPNUtils.setMax(mkg, maxMarking);
				}
				vn.pop();
			}
			logger.info("Initial place(s): {}", markedPlaces.toString());
			long nbUnsafePlaces = unsafePlaces.size64();
			long nbMarkedPlaces = markedPlaces.size64();
			if (nbUnsafePlaces > 0) {
				nupnsb.append(MainPNML2NUPN.PRAGMA_MULTIPLE_INIT_TOKEN).append(NUPNConstants.HK).append(totalMkg)
						.append(NUPNConstants.WS).append(NUPNConstants.HK).append(nbUnsafePlaces)
						.append(NUPNConstants.WS).append(minMarking.getLong(0)).append(NUPNConstants.DOTS)
						.append(maxMarking.getLong(0)).append(NUPNConstants.NL);
				nupnQueue.put(nupnsb.toString());
				clearNUPNStringBuilder();
				logger.warn("There are {} unsafe initial places in this net.", nbUnsafePlaces);
				logger.warn("Unsafe initial places: {}", unsafePlaces.toString());

				logger.info("Checking invariant 'total nb of tokens > nb initial places': {}",
						totalMkg > nbMarkedPlaces);
				logger.info("Checking invariant 'nb unsafe initial places <= nb initial places': {}",
						nbUnsafePlaces <= nbMarkedPlaces);
				logger.info(
						"Checking invariant '(nb_init - nb_places) + (nb_places * min) <= nb_tokens <= (nb_init - nb_places) + (nb_places * max)': {}",
						(nbMarkedPlaces - nbUnsafePlaces) + (nbUnsafePlaces * minMarking.getLong(0)) <= totalMkg
								&& totalMkg <= (nbMarkedPlaces - nbUnsafePlaces)
										+ (nbUnsafePlaces * maxMarking.getLong(0)));
				MainPNML2NUPN.appendMesgLineToSignature(
						"decreased to one the marking of " + nbUnsafePlaces + " initial places");
			}
		} catch (NavExceptionHuge | XPathParseExceptionHuge | XPathEvalExceptionHuge | InterruptedException e) {
			throw new PNMLImportExportException(e);
		}
		ap.resetXPath();
	}

	private void collectNonInitialPlacesNames() throws PNMLImportExportException {
		@SuppressWarnings("unused")
		String pnmlId;
		if (MainPNML2NUPN.isUsePlaceNames()) {
			try {
				vn.toElement(VTDNavHuge.ROOT);
				ap.selectXPath(PNMLPaths.PLACES_PATH_EXCEPT_MKG);
				while ((ap.evalXPath()) != -1) {
					vn.push();
					pnmlId = ExportUtils.getPNMLNodeId(vn, true, plId2NameMap);
					vn.pop();
				}
				ap.resetXPath();
			} catch (NavExceptionHuge | XPathParseExceptionHuge | XPathEvalExceptionHuge e) {
				throw new PNMLImportExportException(e);
			}
		}
	}

	private void collectTransitions() throws PNMLImportExportException {
		String arc, src, trg, id;
		long count = MainPNML2NUPN.getFirstTransitionNumber();
		long tId, pId;
		LongBigArrayBigList pls = null;
		String trLabel;
		try {
			logger.info("Collecting transitions.");
			vn.toElement(VTDNavHuge.ROOT);
			ap.selectXPath(PNMLPaths.TRANSITIONS_PATH);
			while ((ap.evalXPath()) != -1) {
				vn.push();
				id = ExportUtils.getPNMLNodeId(vn, MainPNML2NUPN.isUseTransitionNames(), trId2NameMap);
				tId = count++;
				trId2nupnMap.put(id, tId);
				trLabel = ExportUtils.getPNMLNodeIdOrName(id, MainPNML2NUPN.isUseTransitionNames(), trId2NameMap);
				tsQueue.put(NUPNConstants.T_PREFX + tId + NUPNConstants.WS + trLabel + NUPNConstants.NL);
				labelLength = ExportUtils.updateLabelLength(trLabel, labelLength);
				vn.pop();
			}

			ap.resetXPath();
			vn.toElement(VTDNavHuge.ROOT);
			ap.selectXPath(PNMLPaths.ARCS_PATH);
			while ((ap.evalXPath()) != -1) {
				vn.push();
				pls = null;
				arc = vn.toString(vn.getAttrVal(PNMLPaths.ID_ATTR));
				src = vn.toString(vn.getAttrVal(PNMLPaths.SRC_ATTR));
				trg = vn.toString(vn.getAttrVal(PNMLPaths.TRG_ATTR));

				tId = trId2nupnMap.getLong(src);
				if (tId == -1L) { // transition is the target
					tId = trId2nupnMap.getLong(trg);
					if (tId == -1L) {
						logger.warn(
								"New transition {} referenced by arc {}, that I did not find earlier while parsing all transitions.",
								trg, arc);
						tId = count++;
						trId2nupnMap.put(trg, tId);
						tsQueue.put(NUPNConstants.T_PREFX + tId + NUPNConstants.WS + trg + NUPNConstants.NL);
						labelLength = ExportUtils.updateLabelLength(trg, labelLength);
						logger.warn("Added new transition {} referenced by arc {}.", trg, arc);
					}
					pls = tr2InPlacesMap.get(tId);
					if (pls == null) {
						pls = new LongBigArrayBigList();
						tr2InPlacesMap.put(tId, pls);
					}
					// associate the input place
					pId = plId2nupnMap.getLong(src);
					pls.add(pId);

				} else {// transition is the source
					pls = tr2OutPlacesMap.get(tId);
					if (pls == null) {
						pls = new LongBigArrayBigList();
						tr2OutPlacesMap.put(tId, pls);
					}
					// associate the output place
					pId = plId2nupnMap.getLong(trg);
					pls.add(pId);
				}
				vn.pop();
			}
		} catch (NavExceptionHuge | XPathParseExceptionHuge | XPathEvalExceptionHuge | InterruptedException e) {
			throw new PNMLImportExportException(e);
		}
		ap.resetXPath();
	}

	private void extractUnits() throws PNMLImportExportException {
		try {
			String places = "", subunits = "";
			String[] elemId;
			String unitSId;
			long unitLId;
			long plId;
			LongList placesIntId = new LongArrayList();
			vn.toElement(VTDNavHuge.ROOT);
			ap.selectXPath(PNMLPaths.NUPN_UNIT);
			logger.info("Extracting units.");
			while ((ap.evalXPath()) != -1) {
				vn.push();
				unitSId = vn.toString(vn.getAttrVal(PNMLPaths.ID_ATTR));
				unitLId = unitsIdMap.getLong(unitSId);
				if (unitLId == -1L) {
					unitLId = nupnUnitIdGen++;
					unitsIdMap.put(unitSId, unitLId);
				}
				nupnsb.append("U").append(unitLId);
				// places
				vn.toElement(VTDNavHuge.FIRST_CHILD);
				if (vn.getText() != -1) {
					places = vn.toString(vn.getText());
				}
				if (!places.isEmpty()) {
					elemId = places.split(NUPNConstants.FS);
					for (String s : elemId) {
						plId = plId2nupnMap.getLong(s);
						if (plId == -1L) {
							plId = nupnPlIdGen++;
							plId2nupnMap.put(s, plId);
						}
						placesIntId.add(plId);
					}
					nupnsb.append(NUPNConstants.WS).append(NUPNConstants.HK).append(placesIntId.size());
					PNML2NUPNUtils.debug("Collected places in unit {} ({}): {}", logger, unitSId, unitLId,
							placesIntId.toString());
					if (placesIntId.size() > 1) {
						nupnsb.append(NUPNConstants.WS).append(placesIntId.getLong(0)).append(NUPNConstants.DOTS)
								.append(placesIntId.getLong(placesIntId.size() - 1));
					} else {
						nupnsb.append(NUPNConstants.WS).append(placesIntId.getLong(0)).append(NUPNConstants.DOTS)
								.append(placesIntId.getLong(0));
					}
				} else {
					nupnsb.append(NUPNConstants.WS).append(NUPNConstants.HK).append(NUPNConstants.ZERO)
							.append(NUPNConstants.WS).append(NUPNConstants.ONE).append(NUPNConstants.DOTS)
							.append(NUPNConstants.ZERO);
				}
				// subunits
				vn.toElement(VTDNavHuge.NEXT_SIBLING);
				if (vn.getText() != -1) {
					subunits = vn.toString(vn.getText());
				}
				if (!subunits.isEmpty()) {
					elemId = subunits.split(NUPNConstants.FS);
					nupnsb.append(NUPNConstants.WS).append(NUPNConstants.HK).append(elemId.length);
					for (String s : elemId) {
						unitLId = unitsIdMap.getLong(s);
						if (unitLId == -1L) {
							unitLId = nupnUnitIdGen++;
							unitsIdMap.put(s, unitLId);
						}
						nupnsb.append(NUPNConstants.WS).append(unitLId);
					}
				} else {
					nupnsb.append(NUPNConstants.WS).append(NUPNConstants.HK).append(NUPNConstants.ZERO);
				}
				nupnsb.append(NUPNConstants.NL);

				nupnLines.add(nupnsb.toString());
				clearNUPNStringBuilder();
				placesIntId.clear();
				subunits = "";
				places = "";
				vn.pop();
			}
		} catch (NavExceptionHuge | XPathParseExceptionHuge | XPathEvalExceptionHuge e) {
			throw new PNMLImportExportException(e);
		}
		ap.resetXPath();
	}

	private void extractSizes() throws PNMLImportExportException {
		try {
			logger.info("Extracting sizes.");
			vn.toElement(VTDNavHuge.ROOT);
			ap.selectXPath(PNMLPaths.NUPN_SIZE);
			while ((ap.evalXPath()) != -1) {
				vn.push();
				nbPlaces = Long.parseLong(vn.toString(vn.getAttrVal(PNMLPaths.PLACES_ATTR)));
				nbTrans = Long.parseLong(vn.toString(vn.getAttrVal(PNMLPaths.TRANS_ATTR)));
				nbArcs = Long.parseLong(vn.toString(vn.getAttrVal(PNMLPaths.ARCS_ATTR)));
				vn.pop();
			}
			logger.info("Nb places = {}; nb transitions = {}; nb arcs = {}", nbPlaces, nbTrans, nbArcs);

		} catch (NavExceptionHuge | XPathParseExceptionHuge | XPathEvalExceptionHuge e) {
			throw new PNMLImportExportException(e);
		}
		ap.resetXPath();
	}

	private void extractStructure() throws PNMLImportExportException {
		try {
			logger.info("Extracting NUPN toolinfo structure.");
			vn.toElement(VTDNavHuge.ROOT);
			ap.selectXPath(PNMLPaths.NUPN_STRUCTURE);
			while ((ap.evalXPath()) != -1) {
				vn.push();
				nbUnits = Long.parseLong(vn.toString(vn.getAttrVal(PNMLPaths.UNITS_ATTR)));
				rootUnitId = vn.toString(vn.getAttrVal(PNMLPaths.ROOT_ATTR));
				unitsIdMap.put(rootUnitId, nupnUnitIdGen++);
				isSafe = Boolean.valueOf((vn.toString(vn.getAttrVal(PNMLPaths.SAFE_ATTR))));
				vn.pop();
			}
			logger.info("Nb units = {}; root unit id = {}; is Safe = {}", nbUnits, rootUnitId, isSafe);
			if (isSafe) {
				ExportUtils.insertUnitSafePragma(nupnQueue, null);
			}
		} catch (NavExceptionHuge | XPathParseExceptionHuge | XPathEvalExceptionHuge | InterruptedException e) {
			throw new PNMLImportExportException(e);
		}
		ap.resetXPath();
	}

	private void writeInitialPlaces() throws InterruptedException {
		logger.info("Exporting initial places.");
		long nbMarkedPlaces = markedPlaces.size64();
		if (nbMarkedPlaces > 1) {
			nupnsb.append(NUPNConstants.INIT_PLACES).append(NUPNConstants.WS).append(NUPNConstants.HK)
					.append(nbMarkedPlaces);
			for (String pId : markedPlaces) {
				nupnsb.append(NUPNConstants.WS).append(plId2nupnMap.getLong(pId));
			}
		} else {
			nupnsb.append(NUPNConstants.INIT_PLACE).append(NUPNConstants.WS)
					.append(plId2nupnMap.getLong(markedPlaces.get(0)));
		}
		nupnsb.append(NUPNConstants.NL);
		nupnQueue.put(nupnsb.toString());
		clearNUPNStringBuilder();
	}

	private void writeUnits() throws InterruptedException {
		logger.info("Exporting units.");
		int nbUnits = unitsIdMap.size();
		nupnsb.append(NUPNConstants.UNITS).append(NUPNConstants.WS).append(NUPNConstants.HK).append(nbUnits)
				.append(NUPNConstants.WS).append(NUPNConstants.ZERO).append(NUPNConstants.DOTS).append(nbUnits - 1)
				.append(NUPNConstants.NL);
		nupnsb.append(NUPNConstants.ROOT_UNIT).append(NUPNConstants.WS).append(unitsIdMap.getLong(rootUnitId))
				.append(NUPNConstants.NL);
		nupnQueue.put(nupnsb.toString());
		for (String l : nupnLines) {
			nupnQueue.put(l);
		}
		clearNUPNStringBuilder();
	}

	private void writeNUPNPlaces() throws PNMLImportExportException {
		try {
			logger.info("Exporting places.");
			vn.toElement(VTDNavHuge.ROOT);
			ap.selectXPath(PNMLPaths.COUNT_PLACES_PATH);
			long nbPl = (long) ap.evalXPathToNumber();
			if (nbPl != nbPlaces) {
				logger.error(
						"The number of places I counted in the PNML file ({}) is not equal to the number reported ({}) in the NUPN size element!",
						nbPl, nbPlaces);
				logger.warn("I will output in the NUPN the number reported in the NUPN size element.");
			}
			nupnsb.append(NUPNConstants.PLACES).append(NUPNConstants.WS).append(NUPNConstants.HK).append(nbPlaces)
					.append(NUPNConstants.WS)
					.append(MainPNML2NUPN.getFirstPlaceNumber()).append(NUPNConstants.DOTS).append(nbPlaces - 1L + MainPNML2NUPN.getFirstPlaceNumber())
					.append(NUPNConstants.NL);
			nupnQueue.put(nupnsb.toString());
			clearNUPNStringBuilder();
		} catch (NavExceptionHuge | XPathParseExceptionHuge | InterruptedException e) {
			throw new PNMLImportExportException(e);
		}
		ap.resetXPath();
	}
	
	private void writePlaceLabels() {
		Object2LongLinkedOpenHashMap<String> sortedMap = plId2nupnMap.object2LongEntrySet().stream()
				.sorted(Map.Entry.comparingByValue())
				.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
						(oldValue, newValue) -> oldValue, Object2LongLinkedOpenHashMap::new));
	
		sortedMap.forEach((pnmlId, nupnId) -> {
			final String nupnLabel = ExportUtils.getPNMLNodeIdOrName(pnmlId, MainPNML2NUPN.isUsePlaceNames(),
					plId2NameMap);
			try {
				logger.trace("Outputing mapping between nupn place id {} and its label {}", nupnId, nupnLabel);
				psQueue.put(NUPNConstants.P_PREFX + nupnId + NUPNConstants.WS + nupnLabel + NUPNConstants.NL);
				labelLength = ExportUtils.updateLabelLength(nupnLabel, labelLength);
			} catch (InterruptedException e) {
				logger.error("Error while writing places id-label mappings: {}", e.getMessage(), e);
			}
		});
	}

	private void writeTransitions() throws PNMLImportExportException {
		logger.info("Exporting transitions.");
		try {
			vn.toElement(VTDNavHuge.ROOT);
			ap.selectXPath(PNMLPaths.COUNT_TRANSITIONS_PATH);
			long nbTr = (long) ap.evalXPathToNumber();
			if (nbTr != nbTrans) {
				logger.error(
						"The number of transitions I counted in the PNML file ({}) is not equal to the number reported ({}) in the NUPN size element!",
						nbTr, nbTrans);
				logger.warn("I will output in the NUPN the number reported in the NUPN size element.");
			}
			StringBuilder tsSb = new StringBuilder();
			tsSb.append(NUPNConstants.TRANSITIONS).append(NUPNConstants.WS).append(NUPNConstants.HK).append(nbTrans)
					.append(NUPNConstants.WS)
					.append(MainPNML2NUPN.getFirstTransitionNumber()).append(NUPNConstants.DOTS).append(nbTrans - 1L + MainPNML2NUPN.getFirstTransitionNumber())
					.append(NUPNConstants.NL);
			nupnQueue.put(tsSb.toString());
			tsSb.delete(0, tsSb.length());

			LongCollection allTr = new LongRBTreeSet(trId2nupnMap.values());

			for (long trId : allTr) {
				tsSb.append(NUPNConstants.T).append(trId);
				buildConnectedPlaces2Transition(tsSb, trId, tr2InPlacesMap);
				buildConnectedPlaces2Transition(tsSb, trId, tr2OutPlacesMap);
				tsSb.append(NUPNConstants.NL);
				nupnQueue.put(tsSb.toString());
				tsSb.delete(0, tsSb.length());
			}
			tsSb.delete(0, tsSb.length());
			tsSb = null;
		} catch (InterruptedException | NavExceptionHuge | XPathParseExceptionHuge e) {
			throw new PNMLImportExportException(e);
		}
		ap.resetXPath();
	}

	private void buildConnectedPlaces2Transition(StringBuilder builder, long trId,
			Long2ObjectOpenHashMap<LongBigArrayBigList> tr2PlacesMap) {
		LongBigArrayBigList pls;
		long plsSize;
		pls = tr2PlacesMap.get(trId);
		if (pls != null) {
			plsSize = pls.size64();
		} else { // no place in input or output list of this transition
			plsSize = 0L;
		}
		builder.append(NUPNConstants.WS).append(NUPNConstants.HK).append(plsSize);

		if (plsSize > 0L) {
			for (long plId : pls) {
				builder.append(NUPNConstants.WS).append(plId);
			}
		}
	}

	private void checkAndSetNavAutopilot() throws PNMLImportExportException {
		if (vn == null) {
			vn = PNML2NUPNUtils.openXMLStream(inFile).getNav();
		}
		if (ap == null) {
			ap = new AutoPilotHuge(vn);
		}
	}

	private void openIOChannels() throws IOException {
		outTSFile = new File(PNML2NUPNUtils.extractBaseName(outFile.getCanonicalPath()) + NUPNConstants.TRANS_EXT);
		outPSFile = new File(PNML2NUPNUtils.extractBaseName(outFile.getCanonicalPath()) + NUPNConstants.STATES_EXT);
		ocbNupn = PNML2NUPNUtils.openOutChannel(outFile);
		ocbTs = PNML2NUPNUtils.openOutChannel(outTSFile);
		ocbPs = PNML2NUPNUtils.openOutChannel(outPSFile);
		nupnQueue = PNML2NUPNUtils.initQueue();
		tsQueue = PNML2NUPNUtils.initQueue();
		psQueue = PNML2NUPNUtils.initQueue();

	}

	private void startWriters() {
		nupnWriter = PNML2NUPNUtils.startWriter(ocbNupn, nupnQueue);
		tsWriter = PNML2NUPNUtils.startWriter(ocbTs, tsQueue);
		psWriter = PNML2NUPNUtils.startWriter(ocbPs, psQueue);
	}

	private void emergencyStop(File outFile) throws InterruptedException, IOException {
		stop(outFile);
		logger.error("Emergency stop. Cancelled the translation and released opened resources.");
	}

	private void clearNUPNStringBuilder() {
		nupnsb.delete(0, nupnsb.length());
	}

	private void stop(File outFile) throws InterruptedException, IOException {
		PNML2NUPNUtils.cancelWriters(nupnQueue, tsQueue, psQueue);
		PNML2NUPNUtils.closeChannels(ocbNupn, ocbTs, ocbPs);
		PNML2NUPNUtils.deleteOutputFiles(outFile, outTSFile, outPSFile);
	}

	private void initDataStructures() {
		if (trId2nupnMap == null) {
			trId2nupnMap = new Object2LongOpenHashMap<String>();
			trId2nupnMap.defaultReturnValue(-1L);
		}
		if (trId2NameMap == null) {
			trId2NameMap = new Object2ObjectOpenHashMap<String, String>();
			trId2NameMap.defaultReturnValue("");
		}
		if (tr2InPlacesMap == null) {
			tr2InPlacesMap = new Long2ObjectOpenHashMap<>();
			tr2InPlacesMap.defaultReturnValue(null);
		}
		if (tr2OutPlacesMap == null) {
			tr2OutPlacesMap = new Long2ObjectOpenHashMap<>();
			tr2OutPlacesMap.defaultReturnValue(null);
		}
		if (plId2nupnMap == null) {
			plId2nupnMap = new Object2LongOpenHashMap<String>();
			plId2nupnMap.defaultReturnValue(-1L);
		}
		if (plId2NameMap == null) {
			plId2NameMap = new Object2ObjectOpenHashMap<String, String>();
			plId2NameMap.defaultReturnValue("");
		}
		if (unitsIdMap == null) {
			unitsIdMap = new Object2LongOpenHashMap<>();
			unitsIdMap.defaultReturnValue(-1L);
		}
		markedPlaces = new ObjectBigArrayBigList<>();
		markedPlacesNupnId = new LongBigArrayBigList();
		nupnLines = new ObjectBigArrayBigList<>();
		nupnPlIdGen = MainPNML2NUPN.getFirstPlaceNumber();
		nupnUnitIdGen = 0L;
	}

	/**
	 * Clears all internal data structures for places and transitions.
	 */
	private void clearDataStructures() {
		plId2nupnMap.clear();
		plId2NameMap.clear();
		trId2nupnMap.clear();
		trId2NameMap.clear();
		tr2InPlacesMap.clear();
		tr2OutPlacesMap.clear();
		unitsIdMap.clear();
		markedPlaces.clear();
		markedPlacesNupnId.clear();
		nupnLines.clear();
	}
}
