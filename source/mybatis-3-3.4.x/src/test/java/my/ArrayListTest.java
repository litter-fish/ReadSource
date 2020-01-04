package my;

import java.util.ArrayList;
import java.util.List;

public class ArrayListTest {
    public static void main(String[] args) {
        List<Integer> test = new ArrayList<Integer>();
        for(int offset = 0; offset <20; offset++) {
            test.add(offset);
        }
    }
}
