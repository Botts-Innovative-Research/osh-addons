import os
if os.environ.get("RNS_COMPILE"): from CRNS import RNS
else:                             import RNS

notice = f"Starting tests in {'compiled' if RNS.compiled else 'interpreted'} mode"
print(f"\n{notice}\n"+"="*len(notice)+"\n\n")
