package main;

import main.PackingObjects.Box;
import main.PackingObjects.Pallet;
import main.utils.InputReader;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MainClass {
    public static void main(String[] args)
    {
        InputReader reader = new InputReader();
        Map<String, Box> unpackedBoxes = reader.readData("src\\main\\resources\\test_instance.txt");

        ArrayList<Pallet> pallets = new ArrayList<>();

        /**This block restricts the boxes to those having a dimension equaling to 200mm for comparison to Space Defragmentation 2D**/
        //List<Box> sameHeightBoxes = new BoxCluster().findSameHeightBoxes(unpackedBoxes, 400);
        //PalletBuilder builder = new PalletBuilder(sameHeightBoxes.stream().collect(Collectors.toMap(box->box.getId(), box->box)));
        /***END**/
        PalletBuilder builder = new PalletBuilder(unpackedBoxes);
        try {
            List<Pallet> palletsTest = builder.buildPalletsGreedy();
            //Pallet pallet = builder.buildPallet(true);
            /*if(pallet != null){
                pallets.add(pallet);
            }*/
        } catch (Exception e) {
            e.printStackTrace();
        }
        //TODO: how to recursively build pallets until all boxes are packed
        
    }




}
