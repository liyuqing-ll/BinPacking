package main;

import main.PackingObjects.Box;
import main.PackingObjects.Cuboid;
import main.PackingObjects.FreeSpace3D;
import main.PackingObjects.Pallet;
import main.PlacementObjects.Placement;
import main.PlacementObjects.PositionedRectangle;
import main.State.LayerState;
import main.utils.FreeSpaceComparator;
import main.utils.PackingConfigurationsSingleton;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;
import java.util.stream.Collectors;

import ilog.concert.*;
import ilog.cplex.*;

public class PalletBuilder {
    Map<String, Box> boxesToPack;
    Box box = null;
    LayerBuilder layerBuilder = new LayerBuilder();

    public PalletBuilder(Map<String, Box> boxes)
    {
        boxesToPack = boxes;
        layerBuilder.updateBoxesToPack(boxesToPack);
        //TODO: create pallet dimension object

    }

    //This method builds pallet with a greedy heuristic.
    //Every iteration, it finds a cluster of same height boxes, builds best possible layer within a given number of random shuffles
    public List<Pallet> buildPalletsGreedy() throws IOException {
        BoxCluster boxCluster = new BoxCluster();
        LayerBuilder layerBuilder = new LayerBuilder();
        List<LayerState> layers = new ArrayList<>();
        while(boxesToPack.size() > 0){
            Map<String, Box> maxCluster = boxCluster.getMaxSizeCluster(boxesToPack);
            LayerState layer = layerBuilder.generateBestLayer(maxCluster.values().stream().collect(Collectors.toList()), 40000);
            layers.add(layer);
            boxesToPack.keySet().removeAll(layer.getBoxIds());
        }
        layers = layers.stream().sorted(Comparator.comparing(LayerState::getTotalUsedArea).reversed()).collect(Collectors.toList());
        //Start from layers with few boxes, see if they can be inserted into other layers
        //assuming the layers are sorted in decreasing order of coverage
        //find the layer where the free space area in all preceding layers is greater than all boxes in following layers
        //assuming that all the boxes in following layers oriented with their largest facets as the bottoms

        //do a one pass through the layer and recording both the accumulative free spaces and the accumulative total bottom area of boxes
        int separatingLayerIndex = findSeparatingLayerIndex(layers);
        //iteratively try to insert boxes in layers after separatingLayerIndex into previous layers
        tryIterativeBoxInsertion(layers, separatingLayerIndex, "SIMPLE");

        tryIterativeBoxInsertion(layers, separatingLayerIndex, "SHUFFLE");

        int nbPacks = layers.stream().mapToInt(LayerState::getNumberOfBoxes).sum();
        writeLayers(layers);
        List<List<LayerState>> bins = solveOneDimBinPacking(layers);
        for(List<LayerState> layersInABin: bins){
            layersInABin.stream().sorted(Comparator.comparing(LayerState::getTotalUsedArea).reversed().thenComparing(LayerState::getNbDistinctHeights)).collect(Collectors.toList());
            stackLayers(layersInABin);
        }
        writeBins(bins);
        return null;

    }

    //This method try to insert boxes from sparsely packed layers into densely packed layers
    private void tryIterativeBoxInsertion(List<LayerState> layers, int separatingLayerIndex, String insertionType){
        for(int i = layers.size() - 1; i > separatingLayerIndex; i--){
            LayerState layer = layers.get(i);
            List<String> boxesInserted = new ArrayList<>();
            for(Box box: layer.getPackedBoxes()){
                for(int j = 0; j <= separatingLayerIndex; j++){
                    LayerState accommodatingLayer = layers.get(j);
                    //try insert the box into this layer with different bottom facet subject to height lower than layer height
                    LayerState newLayer = null;
                    int width, depth, height;
                    width = box.getWidth();
                    depth = box.getDepth();
                    height = box.getHeight();
                    if(newLayer == null && height <= accommodatingLayer.getLayerHeight()){
                        newLayer = insertBox(box, accommodatingLayer, insertionType);
                    }
                    if(newLayer == null  && depth <= accommodatingLayer.getLayerHeight()){
                        box.setDims(new Cuboid(width, height, depth));
                        newLayer = insertBox(box, accommodatingLayer, insertionType);
                    }
                    if(newLayer == null  && width <= accommodatingLayer.getLayerHeight()){
                        box.setDims(new Cuboid(height, depth, width));
                        newLayer = insertBox(box, accommodatingLayer, insertionType);
                    }

                    if(newLayer != null){
                        boxesInserted.add(box.getId());
                        layers.set(j, newLayer);
                        break;
                    }
                }
            }
            layer.removeBoxes(boxesInserted);
            int nbPacks = layers.stream().mapToInt(LayerState::getNumberOfBoxes).sum();
            if(nbPacks < 1000)
                System.out.println("Check Nb boxes");
        }
        //remove empty layers
        List<LayerState> layersToRemove = new ArrayList<>();
        for(int i = layers.size() - 1; i > separatingLayerIndex; i--){
            if(layers.get(i).getNumberOfBoxes() == 0)
                layersToRemove.add(layers.get(i));
        }
        layers.removeAll(layersToRemove);
    }

    public LayerState insertBox(Box box, LayerState layer, String insertionType){
        if(layer.getTotalFreeArea() < box.getBottomArea()){
            return null;
        }
        return new LayerBuilder().enhanceLayer(box, layer, insertionType);
    }

    private int findSeparatingLayerIndex(List<LayerState> layers){
        int listLen = layers.size();
        int[] cumuFreeArea = new int [layers.size()+1];
        int[] cumuBottomArea = new int [layers.size()+1];
        cumuFreeArea[0] = 0;
        cumuBottomArea[listLen] = 0;

        for(int i = 0; i < listLen; i++){
            cumuFreeArea[i+1] = cumuFreeArea[i];
            LayerState layer = layers.get(i);
            cumuFreeArea[i+1] += layer.getTotalFreeArea();

            cumuBottomArea[listLen-i-1] = cumuBottomArea[listLen-i];
            layer = layers.get(listLen-i-1);
            for(Box box: layer.getPackedBoxes()){
                cumuBottomArea[listLen-i-1] += box.getLargestFacetArea();
            }
        }
        //Assuming cumuFreeArea > cumuBottomArea only happens once //TODO: prove the correctness of the assumption
        int leftMostCrossingIndex = listLen;
        for(int i = listLen - 1 ; i > 1; i--){
           if(cumuBottomArea[i] < cumuFreeArea[i-1]){
               leftMostCrossingIndex = i-1;
           }
        }
        return leftMostCrossingIndex;
    }
    //TODO change the output to be a list of pallets
    public Pallet buildPallet(boolean buildByLayer) throws Exception {
        Pallet pallet = new Pallet("Pallet", Integer.parseInt(PackingConfigurationsSingleton.getProperty("width")),
                Integer.parseInt(PackingConfigurationsSingleton.getProperty("depth")),
                Integer.parseInt(PackingConfigurationsSingleton.getProperty("height")),
                Integer.parseInt(PackingConfigurationsSingleton.getProperty("capacity")));// while still packable
        if(!buildByLayer){
            //TODO: finish while loop to pack boxes
            box = findPlacement(pallet);
            while(box != null)
            {
                pallet.placeBox(box);
                boxesToPack.remove(box);
                box = findPlacement(pallet);
            }
        }

        if(buildByLayer){
            LayerBuilder layerBuilder = new LayerBuilder();
            layerBuilder.updateBoxesToPack(boxesToPack);
            Map<Integer, List<LayerState>> layersGroupByHeight = layerBuilder.generateLayers(40000, 0.2);
            //solve a set covering problem to cover all boxes with the generated layers using the minimum number of layers
            List<LayerState> selectedLayers = solveSetCovering(layersGroupByHeight);
            //solve a 1-D bin packing problem with layer heights subject to pallet height constraint
            //List<Pallet> pallets = solveOneDimBinPacking(selectedLayers);

            /*ArrayList<LayerState> layers = new ArrayList<>();
            while(!boxesToPack.isEmpty()){
                LayerState layer = layerBuilder.getBestLayer(100000);
                //collect all constructed layers first,
                if(layer != null){
                    layers.add(layer);
                    boxesToPack.keySet().removeAll(layer.getPackedBoxes().stream().map(box1 -> box1.getId()).collect(Collectors.toList()));
                }
            }*/
            //then determine the placing sequence of layers
            //Option 1: rank layers by density, i.e. covered area
            selectedLayers.sort(new Comparator<LayerState>() {
                @Override
                public int compare(LayerState o1, LayerState o2) {
                    if(o1.getTotalUsedArea() > o2.getTotalUsedArea())
                        return -1;
                    else if(o1.getTotalUsedArea() < o2.getTotalUsedArea())
                        return 1;
                    return 0;
                }
            });

            writeLayers(selectedLayers);

            /*for(int i = 0, h = 0, totalWeight = 0; i < layers.size(); i++){
                LayerState layer = layers.get(i);
                if(h + layer.getLayerHeight() < Integer.parseInt(PackingConfigurationsSingleton.getProperty("height"))
                    && totalWeight + layer.getLayerHeight() < Integer.parseInt(PackingConfigurationsSingleton.getProperty("capacity"))
                ){
                    pallet.placeLayer(layer, h);
                    h += layer.getLayerHeight();
                    totalWeight += layer.getTotalWeight();
                }
            }*/

            //TODO option 2, shuffle layers and then stack, choose the best one with maximum density in 3D.


        }

        return pallet;
    }

    private Box findPlacement(Pallet pallet){
        for(Box box: boxesToPack.values()){
            ArrayList<FreeSpace3D> fss = pallet.getFeasibleFreeSpaces(box);
            if(!fss.isEmpty()){
                //add comparator for sorting free spaces
                fss.sort(new FreeSpaceComparator());
                //TODO: currently only placing at front bottom left point, add attemps for placing at other three points
                //TODO: currently returns when a first feasible box placement is found, add comparison of multiple feasible placements
                FreeSpace3D fs = fss.get(0);
                box.setPosition(fs.getPosition());
                //randomly finds a box orientation
                ArrayList<Cuboid> feasibleOrientations = new ArrayList<>();
                Cuboid tempOrientation = box.rotate(0,0,0);
                if(fs.dimensionFits(tempOrientation))
                    feasibleOrientations.add(tempOrientation);
                tempOrientation = box.rotate(0,0,90);
                if(fs.dimensionFits(tempOrientation))
                    feasibleOrientations.add(tempOrientation);
                tempOrientation = box.rotate(0,90,0);
                if(fs.dimensionFits(tempOrientation))
                    feasibleOrientations.add(tempOrientation);
                tempOrientation = box.rotate(0,90,90);
                if(fs.dimensionFits(tempOrientation))
                    feasibleOrientations.add(tempOrientation);
                tempOrientation = box.rotate(90,0,0);
                if(fs.dimensionFits(tempOrientation))
                    feasibleOrientations.add(tempOrientation);
                tempOrientation = box.rotate(90,0,90);
                if(fs.dimensionFits(tempOrientation))
                    feasibleOrientations.add(tempOrientation);
                int randint = (int)(Math.random()*feasibleOrientations.size());
                /*if(box.getId().equals("267984"))
                    randint = 3;
                if(box.getId().equals("1501402"))
                    randint = 4;*/
                Cuboid chosenOrientation = feasibleOrientations.get(randint);
                box.setDims(chosenOrientation);
                return box;
            }
        }
        return null;
    }


    private List<LayerState> solveSetCovering(Map<Integer, List<LayerState>> layersGroupByHeight) throws IloException {
        List<LayerState> result = new ArrayList<>();
        try {
            IloCplex setCoveringSolver = new IloCplex();
            //define objective
            IloObjective layersUsed = setCoveringSolver.addMinimize();
            //define constraints
            Map<String, IloRange> covers = new HashMap<>();

            for(String boxId: boxesToPack.keySet()){
                covers.put(boxId, setCoveringSolver.addRange(1, 1, boxId));
            }
            for(String boxId: boxesToPack.keySet()){
                setCoveringSolver.intVar(setCoveringSolver.column(layersUsed, 1.0).and(setCoveringSolver.column(covers.get(boxId), 1)), 0, 1, boxId);
            }
            //define variables
            Map<String, IloIntVar> vars = new HashMap<>();
            //create model
            for(Map.Entry<Integer, List<LayerState>> pair: layersGroupByHeight.entrySet()){
                List<LayerState> layerList = pair.getValue();
                Integer h = pair.getKey();
                for(int i = 0; i < layerList.size(); i++){
                    LayerState layer = layerList.get(i);
                    //create a column
                    IloColumn col = setCoveringSolver.column(layersUsed, 1.0);
                    for(String boxId: layer.getBoxIds()){
                        col = col.and(setCoveringSolver.column(covers.get(boxId), 1));
                    }
                    String varName = "H"+h+"Id"+i;
                    vars.put(varName, setCoveringSolver.intVar(col, 0, 1, varName));
                }
            }
            //solve
            setCoveringSolver.setParam(	IloCplex.Param.TimeLimit, 3600);
            setCoveringSolver.solve();
            System.out.println("Solution status: " + setCoveringSolver.getStatus());

            for(Map.Entry<String, IloIntVar> pair: vars.entrySet()){
                IloIntVar var = pair.getValue();
                String varName = pair.getKey();
                double value = setCoveringSolver.getValue(var);
                if(value >= 0.9999){
                    List<String> digits = Arrays.asList(varName.replaceAll("[^-?0-9]+", " ").trim().split(" "));
                    Integer clusterHeight = Integer.parseInt(digits.get(0));
                    Integer layerIndex = Integer.parseInt(digits.get(1));
                    result.add(layersGroupByHeight.get(clusterHeight).get(layerIndex));
                }
            }
            setCoveringSolver.end();
        }
        catch ( IloException exc ) {
            System.err.println("Concert exception '" + exc + "' caught");
        }
        Map<String, Integer> boxIds = new HashMap<>();
        for(LayerState layer:result){
            for(String boxId: layer.getBoxIds()){
                    Integer count = boxIds.containsKey(boxId)?boxIds.get(boxId):0;
                    boxIds.put(boxId, count+1);
            }
        }
        Map<String, Integer> overCovered= boxIds.entrySet().stream().filter(entry -> entry.getValue() > 1).collect(Collectors.toMap(entry->entry.getKey(), entry->entry.getValue()));
        List<String> unCovered = new ArrayList<>();
        for(String boxId: boxesToPack.keySet()){
            if(!boxIds.containsKey(boxId)){
                unCovered.add(boxId);
            }
        }
        return result;
    }

    private List<List<LayerState>> solveOneDimBinPacking(List<LayerState> selectedLayers){
        List<List<LayerState>> bins = new ArrayList<>();
        try {
            IloCplex setCoveringSolver = new IloCplex();
            //define objective
            IloObjective binsUsed = setCoveringSolver.addMinimize();
            //define constraints
            Map<String, IloRange> covers = new HashMap<>();
            Map<String, IloRange> boxHeightConstraints = new HashMap<>();
            for(Integer i = 0; i < selectedLayers.size(); i++){
                covers.put("layer_" +i.toString(), setCoveringSolver.addRange(1, 1, "layer_" +i.toString()));
            }
            for(Integer j = 0; j < selectedLayers.size(); j++){
                boxHeightConstraints.put("box_"+j.toString(), setCoveringSolver.addRange(0, 2055, "box_"+j.toString()));
            }

            //define variables
            Map<Integer, IloIntVar> varsBoxUsed = new HashMap<>();
            Map<Integer, Map<Integer, IloIntVar>> varsLayerInBox = new HashMap<>();
            for(Integer i = 0; i < selectedLayers.size(); i++){
                varsLayerInBox.put(i, new HashMap<>());
            }
            for(Integer i = 0; i < selectedLayers.size(); i++){
                LayerState layer = selectedLayers.get(i);
                for(Integer j = 0; j < selectedLayers.size(); j++){
                    //add binary variables x_ij representing layer i in box j
                    varsLayerInBox.get(j).put(i ,setCoveringSolver.intVar(
                            setCoveringSolver.column(boxHeightConstraints.get("box_"+j.toString()), -layer.getLayerHeight())
                            .and(setCoveringSolver.column(covers.get("layer_"+i.toString()), 1)),
                            0,1, "layer_"+i.toString()+"_in_box_"+j.toString()
                    ));
                }
                varsBoxUsed.put(i,
                        setCoveringSolver.intVar(setCoveringSolver.column(binsUsed, 1.0)
                        .and(setCoveringSolver.column(boxHeightConstraints.get("box_"+i.toString()), 2055)), 0, 1, "box_"+i.toString())
                );
            }
            setCoveringSolver.exportModel("test\\oneBin.lp");
            //solve
            setCoveringSolver.setParam(	IloCplex.Param.TimeLimit, 3600);
            setCoveringSolver.solve();
            System.out.println("Solution status: " + setCoveringSolver.getStatus());

            for(Map.Entry<Integer, IloIntVar> pair: varsBoxUsed.entrySet()){
                IloIntVar var = pair.getValue();
                Integer j = pair.getKey();
                double value = setCoveringSolver.getValue(var);
                if(value >= 0.9999){
                    List<LayerState> layers = new ArrayList<>();
                    for(Map.Entry<Integer, IloIntVar> kv: varsLayerInBox.get(j).entrySet()){
                        IloIntVar varLayerInBox = kv.getValue();
                        Integer i = kv.getKey();
                        double value2 = setCoveringSolver.getValue(varLayerInBox);
                        if(value2 >= 0.9999){
                            // layer i in box j
                            layers.add(selectedLayers.get(i));
                        }
                    }
                    bins.add(layers);
                }
            }
            setCoveringSolver.end();
        }
        catch ( IloException exc ) {
            System.err.println("Concert exception '" + exc + "' caught");
        }
        return bins;
    }

    public void stackLayers(List<LayerState> layers){
        int h_position = 0;
        for(LayerState layer: layers){
            for(Placement p: layer.getPlacements().values()){
                p.getPosition().setZ(h_position);
            }
            h_position += layer.getLayerHeight();
        }
    }

    public void writeBins(List<List<LayerState>> bins) throws IOException{
        int i = 0;
        for(List<LayerState> layers: bins){
            FileWriter fileWriter = new FileWriter("test\\bin"+i+".csv");
            PrintWriter printWriter = new PrintWriter(fileWriter);
            for(LayerState layer: layers)
                printWriter.print(layer.toString3D());
            printWriter.close();
            i++;
        }
    }

    public void writeLayers(List<LayerState> layers) throws IOException {
        int i = 0;
        for(LayerState layer: layers){
            FileWriter fileWriter = new FileWriter("test\\layer"+i+".csv");
            PrintWriter printWriter = new PrintWriter(fileWriter);
            printWriter.print(layer.toString2D());
            printWriter.close();
            i++;
        }
    }
}
