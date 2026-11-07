import com.anxiong.watchlink.WatchProfile;
import java.nio.charset.StandardCharsets;

public final class WatchProfileTest {
    private static int checked;
    private static void expect(boolean value, String message) {
        checked++; if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        expect(WatchProfile.isNameCandidate("OnePlus Watch A1B2"),"advertised hex suffix");
        expect(WatchProfile.isNameCandidate("oneplus watch-abcd"),"case normalization");
        expect(WatchProfile.isNameCandidate("W301CN"),"explicit model fallback");
        expect(!WatchProfile.isNameCandidate(null),"missing names");
        expect(!WatchProfile.isNameCandidate("OPPO Watch"),"other brand");
        expect(!WatchProfile.isNameCandidate("OnePlus Watch XYZ"),"invalid suffix");
        expect(!WatchProfile.isNameCandidate("OnePlus Watch 2R"),"different model name");
        expect(WatchProfile.standardValue(WatchProfile.standardUuid("2a19"),new byte[]{0}).equals("0%"),"empty battery is a valid reading");
        expect(WatchProfile.standardValue(WatchProfile.standardUuid("2a19"),new byte[]{100}).equals("100%"),"full battery");
        expect(!WatchProfile.standardValue(WatchProfile.standardUuid("2a19"),new byte[]{(byte)255}).contains("%"),"reject invalid percent");
        expect(!WatchProfile.standardValue(WatchProfile.standardUuid("2a19"),new byte[]{50,1}).contains("%"),"reject malformed length");
        expect(WatchProfile.standardValue(WatchProfile.standardUuid("2a19"),null).equals("空值"),"missing data is not zero percent");
        expect(WatchProfile.standardValue(WatchProfile.standardUuid("2a26"),"A.45\u0000".getBytes(StandardCharsets.UTF_8)).equals("A.45"),"text data terminator");
        String mac="AA:BB:CC:DD:EE:FF";
        expect(WatchProfile.maskedAddress(mac).equals("**:**:**:**:EE:FF"),"mask identifying prefix");
        expect(!WatchProfile.maskedAddress(mac).contains("AA:BB"),"export must omit full address");
        expect(WatchProfile.anonymousId(mac,"one").equals(WatchProfile.anonymousId(mac,"one")),"same-session correlation");
        expect(!WatchProfile.anonymousId(mac,"one").equals(WatchProfile.anonymousId(mac,"two")),"cross-session unlinkability");
        expect(WatchProfile.anonymousId(mac,"one").length()==12,"bounded report identifier");
        expect(WatchProfile.hex(new byte[]{0,15,(byte)255}).equals("000FFF"),"unsigned byte rendering");
        expect(!WatchProfile.COMMAND_UUID.equals(WatchProfile.DATA_UUID),"two distinct transport services");
        System.out.println("PASS: "+checked+" profile, malformed-data and privacy assertions");
    }
}
