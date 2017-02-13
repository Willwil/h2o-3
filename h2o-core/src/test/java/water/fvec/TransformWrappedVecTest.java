package water.fvec;

import org.junit.BeforeClass;
import org.junit.Test;
import water.MRTask;
import water.TestUtil;
import water.rapids.ast.AstRoot;
import water.rapids.Rapids;

public class TransformWrappedVecTest extends TestUtil {
  @BeforeClass static public void setup() {  stall_till_cloudsize(1); }

  @Test public void testInversion() {
    VecAry v=null;
    try {
      v = new VecAry(Vec.makeZero(1<<20));
      AstRoot ast = Rapids.parse("{ x . (- 1 x) }");
      Vec iv = new TransformWrappedVec(v, ast);
      new MRTask() {
        @Override public void map(ChunkAry c) {
          for(int i=0;i<c._len;++i)
            if( c.atd(i)!=1 )
              throw new RuntimeException("moo");
        }
      }.doAll(iv);
      iv.remove();
    } finally {
      if( null!=v ) v.removeVecs();
    }
  }
}
