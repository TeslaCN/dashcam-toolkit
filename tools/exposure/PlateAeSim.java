import icu.wwj.drive.dashcam.PlateAeController;
import java.util.*;

public class PlateAeSim {
    static final int W=960,H=540;
    static final double K=0.25;

    static class Scene {
        String name; double sky, road, wall, plateW, plateChar, light;
        int plateX=430, plateY=360, plateWpx=90, plateHpx=30;
        boolean bluePlate;
        Scene(String n,double sky,double road,double wall,double plateW,double plateChar,double light,boolean blue){
            this.name=n;this.sky=sky;this.road=road;this.wall=wall;this.plateW=plateW;this.plateChar=plateChar;this.light=light;this.bluePlate=blue;
        }
    }

    static byte[] y=new byte[W*H], u=new byte[(W/2)*(H/2)], v=new byte[(W/2)*(H/2)];
    static Random rnd=new Random(7);

    static void render(Scene s,long expNs,int iso){
        double e=expNs/1e9;
        for(int yy=0;yy<H;yy++){
            for(int xx=0;xx<W;xx++){
                double rad;
                int dx=xx-s.plateX, dy=yy-s.plateY;
                boolean inPlate=dx>=0&&dx<s.plateWpx&&dy>=0&&dy<s.plateHpx;
                boolean ch=inPlate && (dx%14>3 && dx%14<11); // 字符条纹
                int lsx=xx-120, lsy=yy-380; boolean light1=lsx*lsx+lsy*lsy<40*40;
                int lsx2=xx-820, lsy2=yy-360; boolean light2=lsx2*lsx2+lsy2*lsy2<35*35;
                if(yy<H*0.32) rad=s.sky;
                else if(yy<H*0.75) rad=s.wall;
                else rad=s.road;
                if(inPlate) rad=ch?s.plateChar:s.plateW;
                if(light1||light2) rad=s.light;
                double val=255.0*rad*e*iso/K + rnd.nextGaussian()*1.2;
                int yv=(int)Math.max(0,Math.min(255,Math.round(val)));
                y[yy*W+xx]=(byte)yv;
            }
        }
        Arrays.fill(u,(byte)128); Arrays.fill(v,(byte)128);
        if(s.bluePlate){
            for(int yy=s.plateY; yy<s.plateY+s.plateHpx; yy++)
                for(int xx=s.plateX; xx<s.plateX+s.plateWpx; xx++){
                    int ui=(yy/2)*(W/2)+(xx/2);
                    u[ui]=(byte)190; v[ui]=(byte)105;
                }
        }
    }

    static PlateAeController.Input input(long exp,int iso,float speed,long now){
        PlateAeController.Input in=new PlateAeController.Input();
        in.y=y;in.width=W;in.height=H;in.rowStride=W;in.pixelStride=1;
        in.u=u;in.v=v;in.uvRowStride=W/2;in.uvPixelStride=1;
        in.exposureNs=exp;in.iso=iso;in.speedKph=speed;in.nowMs=now;in.fps=30;
        return in;
    }

    public static void main(String[] a){
        PlateAeController c=new PlateAeController();
        Scene[] scenes={
            new Scene("night  city",0.002,0.0002,0.0005,0.010,0.001,0.20,true),
            new Scene("day    sun",2.0,0.30,0.60,1.10,0.150,6.0,false),
            new Scene("dusk   city",0.02,0.004,0.006,0.08,0.008,0.5,true),
        };
        float[] speeds={30f,50f,20f};
        long exp=16_666_667L; int iso=800; long now=0;
        for(int si=0;si<scenes.length;si++){
            Scene s=scenes[si];
            System.out.println("=== "+s.name+" speed="+speeds[si]);
            for(int step=0;step<40;step++){
                render(s,exp,iso);
                PlateAeController.Output o=c.onFrame(input(exp,iso,speeds[si],now));
                if(step%5==0||o.changed)
                    System.out.printf("t=%4d plate=%d p90=%5.1f clip=%4.1f%% amb=%5.1f exp=%8.3fms iso=%4d err=%+.2f scene=%s note=%s%n",
                        step,o.plateBlocks,o.plateP90,o.plateClipPct,o.ambient,o.exposureNs/1e6,o.iso,o.errorEv,
                        PlateAeController.sceneName(o.scene),o.note);
                exp=o.exposureNs; iso=o.iso; now+=160;
            }
        }
    }
}
